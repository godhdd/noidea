package com.openxc.remote;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import java.net.URISyntaxException;
import java.net.URI;

import java.util.Collections;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Objects;

import com.openxc.measurements.Latitude;
import com.openxc.measurements.Longitude;
import com.openxc.measurements.VehicleSpeed;

import com.openxc.remote.RemoteVehicleServiceListenerInterface;

import com.openxc.remote.sources.AbstractVehicleDataSourceCallback;

import com.openxc.remote.sources.usb.UsbVehicleDataSource;

import com.openxc.remote.sources.VehicleDataSourceCallbackInterface;
import com.openxc.remote.sources.VehicleDataSourceInterface;

import com.openxc.remote.sinks.VehicleDataSinkInterface;
import com.openxc.remote.sinks.FileRecorderSink;

import android.app.Service;

import android.content.Context;
import android.content.Intent;

import android.location.Location;
import android.location.LocationManager;
import android.location.LocationListener;

import android.os.Looper;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

import android.util.Log;

/**
 * The RemoteVehicleService is the centralized source of all vehicle data.
 *
 * To minimize overhead, only one object connects to the current vehicle data
 * source (e.g. a CAN translator or trace file being played back) and all
 * application requests are eventually propagated back to this service.
 *
 * Applications should not use this service directly, but should bind to the
 * in-process {@link com.openxc.VehicleService} instead - that has an interface
 * that respects Measurement types. The interface used for the
 * RemoteVehicleService is purposefully primative as there are a small set of
 * objects that can be natively marshalled through an AIDL interface.
 *
 * The service initializes and connects to the vehicle data source when bound.
 * The data source is selected by the application by passing extra data along
 * with the bind Intent - see the {@link #onBind(Intent)} method for details.
 * Only one data source is supported at a time.
 *
 * When a message is received from the data source, it is passed to any and all
 * registered message "sinks" - these receivers conform to the
 * {@link com.openxc.remote.sinks.VehicleDataSinkInterface}. There will always
 * be at least one sink that stores the latest messages and handles passing on
 * data to users of the VehicleService class. Other possible sinks include the
 * {@link com.openxc.remote.sinks.FileRecorderSink} which records a trace of the
 * raw OpenXC measurements to a file and a web streaming sink (which streams the
 * raw data to a web application). Users cannot register additional sinks at
 * this time, but the feature is planned.
 */
public class RemoteVehicleService extends Service {
    private final static String TAG = "RemoteVehicleService";
    private final static String DEFAULT_DATA_SOURCE =
            UsbVehicleDataSource.class.getName();
    private final static int NATIVE_GPS_UPDATE_INTERVAL = 5000;
    public final static String VEHICLE_LOCATION_PROVIDER = "vehicle";

    private int mMessagesReceived = 0;
    private Map<String, RawMeasurement> mMeasurements;
    private VehicleDataSourceInterface mDataSource;
    private VehicleDataSinkInterface mDataSink;

    private Map<String, RemoteCallbackList<
        RemoteVehicleServiceListenerInterface>> mListeners;
    private BlockingQueue<String> mNotificationQueue;
    private NotificationThread mNotificationThread;
    private LocationManager mLocationManager;
    private NativeLocationListener mNativeLocationListener;
    private WakeLock mWakeLock;

    /**
     * A callback receiver for the vehicle data source.
     *
     * The selected vehicle data source is initialized with this callback object
     * and calls its receive() methods with new values as they come in - it's
     * important that receive() not block in order to get out of the way of new
     * meausrements coming in on a physical vehcile interface.
     */
    VehicleDataSourceCallbackInterface mCallback =
        new AbstractVehicleDataSourceCallback () {
            private void updateLocation() {
                if(mLocationManager == null ||
                        !mMeasurements.containsKey(Latitude.ID) ||
                        !mMeasurements.containsKey(Longitude.ID) ||
                        !mMeasurements.containsKey(VehicleSpeed.ID)) {
                    return;
                }

                Location location = new Location(LocationManager.GPS_PROVIDER);
                location.setLatitude(mMeasurements.get(Latitude.ID)
                        .getValue().doubleValue());
                location.setLongitude(mMeasurements.get(Longitude.ID)
                        .getValue().doubleValue());
                location.setSpeed(mMeasurements.get(VehicleSpeed.ID)
                        .getValue().floatValue());
                location.setTime(System.currentTimeMillis());

                try {
                    mLocationManager.setTestProviderLocation(
                            LocationManager.GPS_PROVIDER, location);
                    location.setProvider(VEHICLE_LOCATION_PROVIDER);
                    mLocationManager.setTestProviderLocation(
                            VEHICLE_LOCATION_PROVIDER, location);
                } catch(SecurityException e) {
                    Log.w(TAG, "Unable to use mocked locations, " +
                            "insufficient privileges", e);
                }
            }

            private void receive(final String measurementId,
                    final RawMeasurement measurement) {
                mMeasurements.put(measurementId, measurement);
                if(mListeners.containsKey(measurementId)) {
                    try  {
                        mNotificationQueue.put(measurementId);
                    } catch(InterruptedException e) {}
                }
            }

            private void receiveRaw(final String measurementId,
                    Object value) {
                receiveRaw(measurementId, value, null);
            }

            private void receiveRaw(final String measurementId,
                    Object value, Object event) {
                if(mDataSink != null) {
                    mDataSink.receive(measurementId, value, event);
                }
                mMessagesReceived++;
            }

            public void receive(String measurementId, Object value) {
                RawMeasurement measurement =
                        RawMeasurement.measurementFromObjects(value);
                receive(measurementId, measurement);
                receiveRaw(measurementId, value);

                if(measurementId.equals(Latitude.ID) ||
                        measurementId.equals(Longitude.ID)) {
                    updateLocation();
                }
            }

            public void receive(String measurementId, Object value,
                    Object event) {
                RawMeasurement measurement =
                    RawMeasurement.measurementFromObjects(value, event);
                receive(measurementId, measurement);
                receiveRaw(measurementId, value, event);
            }
        };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service starting");
        mMeasurements = new HashMap<String, RawMeasurement>();
        mNotificationQueue = new LinkedBlockingQueue<String>();
        mListeners = Collections.synchronizedMap(
                new HashMap<String, RemoteCallbackList<
                RemoteVehicleServiceListenerInterface>>());
        setupMockLocations();
        acquireWakeLock();
    }

    /**
     * Shut down any associated services when this service is about to die.
     *
     * This stops the data source (e.g. stops trace playback) and kills the
     * thread used for notifying measurement listeners.
     */
    @Override
    public void onDestroy() {
        Log.i(TAG, "Service being destroyed");
        if(mDataSource != null) {
            mDataSource.stop();
            mDataSource = null;
        }

        if(mNotificationThread != null) {
            mNotificationThread.done();
            mNotificationThread = null;
        }
        releaseWakeLock();
        // TODO loop over and kill all callbacks in remote callback list
    }

    /**
     * Initialize the service and data source when a client binds to us.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "Service binding in response to " + intent);
        if(mNotificationThread != null) {
            mNotificationThread.done();
        }
        mNotificationThread = new NotificationThread();
        mNotificationThread.start();
        initializeDataSource();
        return mBinder;
    }

    /**
     * Reset the data source to the default when all clients disconnect.
     *
     * Since normal service users that want the default (i.e. USB device) don't
     * call setDataSource, they get stuck in a situation where a trace file
     * is being used.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        initializeDataSource();
        return false;
    }


    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("dataSource", mDataSource)
            .add("numListeners", mListeners.size())
            .add("numMeasurementTypes", mMeasurements.size())
            .add("callbackBacklog", mNotificationQueue.size())
            .toString();
    }

    /**
     * Setup Android location framework to accept vehicle GPS.
     *
     * If we have at least latitude, longitude and vehicle speed from
     * the vehicle, we send out a mocked location for the
     * LocationManager.GPS_PROVIDER and VEHICLE_LOCATION_PROVIDER
     * providers.
     *
     * Developers can either use the standard Android location framework
     * with mocked locations enabled, or the specific OpenXC
     * Latitude/Longitude measurements.
     */
    private void setupMockLocations() {
        mLocationManager = (LocationManager) getSystemService(
                Context.LOCATION_SERVICE);
        try {
            mLocationManager.addTestProvider(LocationManager.GPS_PROVIDER,
                    false, false, false, false, false, true, false, 0, 5);
            mLocationManager.setTestProviderEnabled(
                    LocationManager.GPS_PROVIDER, true);

            if(mLocationManager.getProvider(
                        VEHICLE_LOCATION_PROVIDER) == null) {
                mLocationManager.addTestProvider(VEHICLE_LOCATION_PROVIDER,
                        false, false, false, false, false, true, false, 0, 5);
            }
            mLocationManager.setTestProviderEnabled(
                    VEHICLE_LOCATION_PROVIDER, true);
        } catch(SecurityException e) {
            Log.w(TAG, "Unable to use mocked locations, " +
                    "insufficient privileges", e);
            mLocationManager = null;
        }
    }

    private void initializeDataSource() {
        initializeDataSource(DEFAULT_DATA_SOURCE, null);
    }

    private void initializeDataSource(
            String dataSourceName, String resource) {
        if(mDataSource != null) {
            mDataSource.stop();
            mDataSource = null;
        }

        Class<? extends VehicleDataSourceInterface> dataSourceType;
        try {
            dataSourceType = Class.forName(dataSourceName).asSubclass(
                    VehicleDataSourceInterface.class);
        } catch(ClassNotFoundException e) {
            Log.w(TAG, "Couldn't find data source type " + dataSourceName, e);
            return;
        }

        Constructor<? extends VehicleDataSourceInterface> constructor;
        try {
            constructor = dataSourceType.getConstructor(Context.class,
                    VehicleDataSourceCallbackInterface.class, URI.class);
        } catch(NoSuchMethodException e) {
            Log.w(TAG, dataSourceType + " doesn't have a proper constructor");
            return;
        }

        URI resourceUri = null;
        if(resource != null) {
            try {
                resourceUri = new URI(resource);
            } catch(URISyntaxException e) {
                Log.w(TAG, "Unable to parse resource as URI " + resource);
            }
        }

        VehicleDataSourceInterface dataSource = null;
        try {
            dataSource = constructor.newInstance(this, mCallback, resourceUri);
        } catch(InstantiationException e) {
            Log.w(TAG, "Couldn't instantiate data source " + dataSourceType, e);
        } catch(IllegalAccessException e) {
            Log.w(TAG, "Default constructor is not accessible on " +
                    dataSourceType, e);
        } catch(InvocationTargetException e) {
            Log.w(TAG, dataSourceType + "'s constructor threw an exception",
                    e);
        }

        if(dataSource != null) {
            Log.i(TAG, "Initializing vehicle data source " + dataSource);
            new Thread(dataSource).start();
        }

        mDataSource = dataSource;
    }

    private RemoteCallbackList<RemoteVehicleServiceListenerInterface>
            getOrCreateCallbackList(String measurementId) {
        RemoteCallbackList<RemoteVehicleServiceListenerInterface>
            callbackList = mListeners.get(measurementId);
        if(callbackList == null) {
            callbackList = new RemoteCallbackList<
                RemoteVehicleServiceListenerInterface>();
            mListeners.put(measurementId, callbackList);
        }
        return callbackList;
    }

    private final RemoteVehicleServiceInterface.Stub mBinder =
        new RemoteVehicleServiceInterface.Stub() {
            public RawMeasurement get(String measurementId)
                    throws RemoteException {
                return getMeasurement(measurementId);
            }

            public void addListener(String measurementId,
                    RemoteVehicleServiceListenerInterface listener) {
                Log.i(TAG, "Adding listener " + listener + " to " +
                        measurementId);
                getOrCreateCallbackList(measurementId).register(listener);

                if(mMeasurements.containsKey(measurementId)) {
                    // send the last known value to the new listener
                    RawMeasurement rawMeasurement =
                        getMeasurement(measurementId);
                    try {
                        listener.receive(measurementId, rawMeasurement);
                    } catch(RemoteException e) {
                        Log.w(TAG, "Couldn't notify application " +
                                "listener -- did it crash?", e);
                    }
                }
            }

            public void removeListener(String measurementId,
                    RemoteVehicleServiceListenerInterface listener) {
                Log.i(TAG, "Removing listener " + listener + " from " +
                        measurementId);
                getOrCreateCallbackList(measurementId).unregister(listener);
            }

            public void setDataSource(String dataSource, String resource) {
                Log.i(TAG, "Setting data source to " + dataSource +
                        " with resource " + resource);
                initializeDataSource(dataSource, resource);
            }

            public void enableRecording(boolean enabled) {
                Log.i(TAG, "Setting trace recording status to " + enabled);
                RemoteVehicleService.this.enableRecording(enabled);
            }

            public void enableNativeGpsPassthrough(boolean enabled) {
                Log.i(TAG, "Setting native GPS passtrough status to " +
                        enabled);
                RemoteVehicleService.this.enableNativeGpsPassthrough(enabled);
            }

            public int getMessageCount() {
                return RemoteVehicleService.this.getMessageCount();
            }
    };

    private class NotificationThread extends Thread {
        private boolean mRunning = true;

        private synchronized boolean isRunning() {
            return mRunning;
        }

        public synchronized void done() {
            Log.d(TAG, "Stopping notification thread");
            mRunning = false;
            // A context switch right can cause a race condition if we
            // used take() instead of poll(): when mRunning is set to
            // false and interrupt is called but we haven't called
            // take() yet, so nobody is waiting. By using poll we can not be
            // locked for more than 1s.
            interrupt();
        }

        public void run() {
            while(isRunning()) {
                String measurementId = null;
                try {
                    measurementId = mNotificationQueue.poll(1,
                            TimeUnit.SECONDS);
                } catch(InterruptedException e) {
                    Log.d(TAG, "Interrupted while waiting for a new " +
                            "item for notification -- likely shutting down");
                    return;
                }

                if(measurementId == null) {
                    continue;
                }

                RemoteCallbackList<RemoteVehicleServiceListenerInterface>
                    callbacks = mListeners.get(measurementId);
                RawMeasurement rawMeasurement =
                    getMeasurement(measurementId);
                propagateMeasurement(callbacks, measurementId, rawMeasurement);
            }
            Log.d(TAG, "Stopped USB listener");
        }
    };

    private void propagateMeasurement(
            RemoteCallbackList<RemoteVehicleServiceListenerInterface> callbacks,
            String measurementId,
            RawMeasurement measurement) {
        int i = callbacks.beginBroadcast();
        while(i > 0) {
            i--;
            try {
                callbacks.getBroadcastItem(i).receive(measurementId,
                        measurement);
            } catch(RemoteException e) {
                Log.w(TAG, "Couldn't notify application " +
                        "listener -- did it crash?", e);
            }
        }
        callbacks.finishBroadcast();
    }

    private class NativeLocationListener extends Thread implements LocationListener {
        public void run() {
            Looper.myLooper().prepare();
            LocationManager locationManager = (LocationManager)
                getSystemService(Context.LOCATION_SERVICE);

            // try to grab a rough location from the network provider before
            // registering for GPS, which may take a while to initialize
            Location lastKnownLocation = locationManager.getLastKnownLocation(
                        LocationManager.NETWORK_PROVIDER);
            if(lastKnownLocation != null) {
                onLocationChanged(lastKnownLocation);
            }

            try {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        NATIVE_GPS_UPDATE_INTERVAL, 0,
                        this);
                Log.d(TAG, "Requested GPS updates");
            } catch(IllegalArgumentException e) {
                Log.w(TAG, "GPS location provider is unavailable");
            }
            Looper.myLooper().loop();
        }

        public void onLocationChanged(final Location location) {
            mCallback.receive(Latitude.ID, location.getLatitude());
            mCallback.receive(Longitude.ID, location.getLongitude());
        }

        public void onStatusChanged(String provider, int status,
                Bundle extras) {}
        public void onProviderEnabled(String provider) {}
        public void onProviderDisabled(String provider) {}
    };

    private RawMeasurement getMeasurement(String measurementId) {
        RawMeasurement rawMeasurement = mMeasurements.get(measurementId);
        if(rawMeasurement == null) {
            rawMeasurement = new RawMeasurement();
        }
        return rawMeasurement;
    }

    private void enableRecording(boolean enabled) {
        if(enabled && mDataSink == null) {
            mDataSink = new FileRecorderSink(this);
            Log.i(TAG, "Initialized vehicle data sink " + mDataSink);
        } else if(mDataSink != null) {
            mDataSink.stop();
            mDataSink = null;
        }
    }

    private void enableNativeGpsPassthrough(boolean enabled) {
        if(enabled) {
            Log.i(TAG, "Enabled native GPS passthrough");
            mNativeLocationListener = new NativeLocationListener();
            mNativeLocationListener.start();
        } else if(mNativeLocationListener != null) {
            LocationManager locationManager = (LocationManager)
                getSystemService(Context.LOCATION_SERVICE);
            Log.i(TAG, "Disabled native GPS passthrough");
            locationManager.removeUpdates(mNativeLocationListener);
            mNativeLocationListener = null;
        }
    }

    private int getMessageCount() {
        return mMessagesReceived;
    }

    private void acquireWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.acquire();
    }

    private void releaseWakeLock() {
        if(mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
    }
}

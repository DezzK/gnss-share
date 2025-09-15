package com.gnssshare.client;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import androidx.annotation.NonNull;

import com.gnssshare.proto.LocationProto;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class GNSSClientService extends Service implements ConnectionManager.ConnectionListener {
    private static final String TAG = "GNSSClientService";
    private static final String WAKELOCK_TAG = "GNSSClientService:WakeLockTag";
    ;
    private static final String SERVER_IP = "192.168.43.1";
    private static final int SERVER_PORT = 8887;

    private static final String CHANNEL_ID = "GNSSClientChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_IS_SERVICE_ENABLED = "isServiceEnabled";

    private static boolean running = false;

    private ConnectionManager connectionManager;
    private LocationManager locationManager;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean isReceivingUpdates = new AtomicBoolean(false);

    private Socket currentSocket;
    private Location lastReceivedLocation;
    private long lastUpdateTime;

    public static boolean isServiceRunning() {
        return GNSSClientService.running;
    }

    private ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
            connectionManager.onNetworkAvailable();
        }

        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);
            connectionManager.onNetworkLost();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = getSystemService(NotificationManager.class);
        locationManager = getSystemService(LocationManager.class);
        connectionManager = new ConnectionManager(SERVER_IP, SERVER_PORT, this);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        // Register WiFi state receiver
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);

        createNotificationChannel();

        startForeground(NOTIFICATION_ID, createNotification(false));

        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // SharedPreferences are managed by MainActivity, don't override here

        // Start connection attempt
        connectionManager.connect();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;

        super.onDestroy();

        // SharedPreferences are managed by MainActivity, don't override here

        if (connectionManager != null) {
            connectionManager.shutdown();
        }
        executor.shutdown();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new GNSSClientBinder(this);
    }

    // ConnectionManager.ConnectionListener implementation
    @Override
    public void onConnectionStateChanged(ConnectionManager.ConnectionState state, String message) {
        Log.d(TAG, "Connection state: " + state + " - " + message);

        // Update notification
        updateNotification();

        // Notify activity about connection status change
        sendBroadcast(new Intent("com.gnssshare.CONNECTION_CHANGED")
                .putExtra("connected", state == ConnectionManager.ConnectionState.CONNECTED));
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public void onConnectionEstablished(Socket socket) {
        Log.i(TAG, "Connection established, starting location updates");

        this.currentSocket = socket;
        startReceivingLocationUpdates();

        if (wakeLock != null) {
            wakeLock.acquire();
        }
    }

    @Override
    public void onConnectionLost() {
        Log.i(TAG, "Connection lost, stopping location updates");

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        stopReceivingLocationUpdates();
        this.currentSocket = null;

        // Notify activity about disconnection
        sendBroadcast(new Intent("com.gnssshare.CONNECTION_CHANGED")
                .putExtra("connected", false));
    }

    private void startReceivingLocationUpdates() {
        if (isReceivingUpdates.get() || currentSocket == null) {
            return;
        }

        isReceivingUpdates.set(true);

        if (!isMockLocationEnabled()) {
            Log.w(TAG, "Mock locations not enabled - please enable in Developer Options");
            broadcastMockLocationStatus(getString(R.string.mock_location_enable_message));
            return;
        }

        // Add mock location provider
        try {
            setupMockLocationProvider();
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied));
            return;
        } catch (Exception e) {
            Log.e(TAG, "Error setting up mock location provider", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()));
            return;
        }

        executor.execute(() -> {
            try {
                InputStream inputStream = currentSocket.getInputStream();

                while (isReceivingUpdates.get() && !currentSocket.isClosed()) {
                    try {
                        // Read message length (4 bytes)
                        byte[] lengthBytes = new byte[4];
                        int bytesRead = 0;
                        while (bytesRead < 4) {
                            int read = inputStream.read(lengthBytes, bytesRead, 4 - bytesRead);
                            if (read == -1) {
                                throw new IOException("Connection closed by server");
                            }
                            bytesRead += read;
                        }

                        int messageLength = bytesToInt(lengthBytes);

                        // Read message data
                        byte[] messageData = new byte[messageLength];
                        bytesRead = 0;
                        while (bytesRead < messageLength) {
                            int read = inputStream.read(messageData, bytesRead, messageLength - bytesRead);
                            if (read == -1) {
                                throw new IOException("Connection closed by server");
                            }
                            bytesRead += read;
                        }

                        // Parse protobuf message
                        LocationProto.ServerResponse response =
                                LocationProto.ServerResponse.parseFrom(messageData);

                        if (response.hasStatus()) {
                            Log.i(TAG, "Server status: " + response.getStatus());
                        }

                        if (response.hasLocationUpdate()) {
                            handleLocationUpdate(response.getLocationUpdate());
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error receiving location update", e);
                        // Let ConnectionManager handle the reconnection
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error in location update receiver", e);
            }

            connectionManager.disconnect();
            connectionManager.scheduleReconnect();
        });
    }

    private void stopReceivingLocationUpdates() {
        isReceivingUpdates.set(false);

        // Stop providing mock locations
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            // Provider might not be added yet
        }
    }

    private void setupMockLocationProvider() {
        // Remove existing test provider if it exists
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (IllegalArgumentException e) {
            // Provider doesn't exist, which is fine
            Log.d(TAG, "GPS test provider doesn't exist, creating new one");
        }

        // Add test provider with correct parameters
        locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, // requiresNetwork - GPS doesn't require network
                true,  // requiresSatellite - GPS uses satellites
                false, // requiresCell - GPS doesn't require cell
                false, // hasMonetaryCost - GPS is free
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                ProviderProperties.POWER_USAGE_HIGH, // powerRequirement
                ProviderProperties.ACCURACY_FINE // accuracy
        );

        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
        Log.i(TAG, "Mock location provider setup successfully");
        broadcastMockLocationStatus(getString(R.string.mock_location_provider_ready));
    }

    private int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }

    private void handleLocationUpdate(LocationProto.LocationUpdate locationUpdate) {
        try {
            // Create Android Location object
            Location location = new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(locationUpdate.getLatitude());
            location.setLongitude(locationUpdate.getLongitude());
            location.setTime(locationUpdate.getTimestamp());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            location.setAltitude(locationUpdate.getAltitude());
            location.setAccuracy(locationUpdate.getAccuracy());
            location.setBearing(locationUpdate.getBearing());
            location.setSpeed(locationUpdate.getSpeed());

            // Set mock location
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);

            // Update internal state
            lastReceivedLocation = location;
            lastUpdateTime = System.currentTimeMillis();

            // Update notification with new location data
            updateNotification();

            // Broadcast location update to activity
            Intent intent = new Intent("com.gnssshare.LOCATION_UPDATE");
            intent.putExtra("location", location);
            intent.putExtra("satellites", locationUpdate.getSatellites());
            intent.putExtra("provider", locationUpdate.getProvider());
            intent.putExtra("locationAge", locationUpdate.getLocationAge());
            sendBroadcast(intent);

        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location", e);
        }
    }

    private void broadcastMockLocationStatus(String message) {
        Intent intent = new Intent("com.gnssshare.MOCK_LOCATION_STATUS");
        intent.putExtra("message", message);
        sendBroadcast(intent);
    }

    // Public methods for activity binding
    public boolean isConnectedToServer() {
        return connectionManager != null && connectionManager.isConnected();
    }

    public Location getLastReceivedLocation() {
        return lastReceivedLocation;
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    // Binder class for activity communication
    public static class GNSSClientBinder extends android.os.Binder {
        private final GNSSClientService service;

        public GNSSClientBinder(GNSSClientService service) {
            this.service = service;
        }

        public GNSSClientService getService() {
            return service;
        }
    }

    public boolean isMockLocationEnabled() {
        try {
            return android.provider.Settings.Secure.getString(getContentResolver(), "mock_location") != null;
        } catch (Exception e) {
            Log.e(TAG, "Error checking mock location setting", e);
            return false;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(String.format(getString(R.string.notification_channel_description), getString(R.string.app_name)));

        notificationManager.createNotificationChannel(channel);
    }

    private Notification createNotification(boolean isConnected) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = isConnected ?
                String.format(getString(R.string.notification_title_connected), getString(R.string.app_name)) :
                String.format(getString(R.string.notification_title_disconnected), getString(R.string.app_name));

        String text = isConnected ?
                (lastReceivedLocation != null ?
                        String.format(getString(R.string.notification_text_connected),
                                (System.currentTimeMillis() - lastUpdateTime) / 1000.0) :
                        getString(R.string.notification_text_connected_no_age)) :
                getString(R.string.notification_text_disconnected);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        boolean isConnected = connectionManager != null && connectionManager.isConnected();

        notificationManager.notify(NOTIFICATION_ID, createNotification(isConnected));
    }

    // SharedPreferences helper methods
    public static void setServiceEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_IS_SERVICE_ENABLED, enabled).apply();
    }

    public static boolean isServiceEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_IS_SERVICE_ENABLED, false);
    }

    private static SharedPreferences getPrefs(Context context) {
        final Context deviceContext = context.createDeviceProtectedStorageContext();
        return deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", MODE_PRIVATE);
    }
}

package com.gnssshare.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GnssStatus;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.gnssshare.proto.LocationProto;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GNSSServerService extends Service {
    private static final String TAG = "GNSSServerService";
    private static final int PORT = 8887;
    private static final String CHANNEL_ID = "GNSSServerChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_IS_SERVICE_ENABLED = "isServiceEnabled";

    private static boolean running = false;

    private ServerSocket serverSocket;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private NotificationManager notificationManager;

    private final CopyOnWriteArrayList<ClientHandler> connectedClients = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Location lastLocation;
    private int satelliteCount = 0;
    private boolean isGnssActive = false;

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = getSystemService(NotificationManager.class);

        createNotificationChannel();
        initializeLocationManager();

        startForeground(NOTIFICATION_ID, createNotification());

        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startServer();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;

        super.onDestroy();

        stopServer();
        stopLocationUpdates();
        executor.shutdown();

        notificationManager.cancel(NOTIFICATION_ID);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initializeLocationManager() {
        locationManager = getSystemService(LocationManager.class);

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                handleLocationUpdate(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
                Log.d(TAG, "Provider enabled: " + provider);
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Log.d(TAG, "Provider disabled: " + provider);
            }
        };

        GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                if (GNSSServerService.isServiceRunning()) {
                    satelliteCount = status.getSatelliteCount();
                    mainHandler.post(() -> updateNotification("satellites status changed"));
                }
            }
        };

        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);

            Log.d(TAG, "GNSS status callback registered");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to register GNSS status callback", e);
        }
    }

    private void startServer() {
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "Server started on port " + PORT);

                while (!serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        Log.d(TAG, "Client connected: " + clientSocket.getRemoteSocketAddress());

                        ClientHandler clientHandler = new ClientHandler(clientSocket);
                        connectedClients.add(clientHandler);
                        executor.execute(clientHandler);

                        // Start location updates when first client connects
                        if (connectedClients.size() == 1) {
                            mainHandler.post(this::startLocationUpdates);
                        }

                        updateNotification("New client connected");
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) {
                            Log.e(TAG, "Error accepting client connection", e);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error starting server", e);
            }
        });
    }

    private void stopServer() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }

            for (ClientHandler client : connectedClients) {
                client.disconnect();
            }
            connectedClients.clear();
        } catch (IOException e) {
            Log.e(TAG, "Error stopping server", e);
        }
    }

    private void startLocationUpdates() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::startLocationUpdates);
            return;
        }

        try {
            Log.d(TAG, "Starting location updates...");

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000, // 1 second
                    0,    // 0 meters
                    locationListener
            );

            Log.d(TAG, "Location updates started");

            isGnssActive = locationManager.isLocationEnabled();

            updateNotification("Started location updates");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting location updates", e);
        }
    }

    private void stopLocationUpdates() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::stopLocationUpdates);
            return;
        }

        Log.d(TAG, "Stopping location updates...");

        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }

        Log.d(TAG, "Location updates stopped");

        isGnssActive = false;

        updateNotification("Stopped location updates");
    }

    private void handleLocationUpdate(Location location) {
        lastLocation = location;

        // Create protobuf message
        LocationProto.LocationUpdate.Builder builder = LocationProto.LocationUpdate.newBuilder()
                .setTimestamp(location.getTime())
                .setLatitude(location.getLatitude())
                .setLongitude(location.getLongitude())
                .setProvider(location.getProvider())
                .setSatellites(satelliteCount)
                .setLocationAge((System.currentTimeMillis() - location.getTime()) / 1000.0f);

        if (location.hasAltitude()) {
            builder.setAltitude(location.getAltitude());
        }
        if (location.hasAccuracy()) {
            builder.setAccuracy(location.getAccuracy());
        }
        if (location.hasBearing()) {
            builder.setBearing(location.getBearing());
        }
        if (location.hasSpeed()) {
            builder.setSpeed(location.getSpeed());
        }
        if (location.hasVerticalAccuracy()) {
            builder.setVerticalAccuracy(location.getVerticalAccuracyMeters());
        }
        if (location.hasBearingAccuracy()) {
            builder.setBearingAccuracy(location.getBearingAccuracyDegrees());
        }
        if (location.hasSpeedAccuracy()) {
            builder.setSpeedAccuracy(location.getSpeedAccuracyMetersPerSecond());
        }

        LocationProto.LocationUpdate locationUpdate = builder.build();

        Log.d(TAG, "Broadcasting location to " + connectedClients.size() + " clients: " + location);

        // Broadcast to all connected clients
        executor.execute(() -> broadcastLocationUpdate(locationUpdate));
        updateNotification("Sent location update to clients");
    }

    private void broadcastLocationUpdate(LocationProto.LocationUpdate locationUpdate) {
        for (ClientHandler client : connectedClients) {
            client.sendLocationUpdate(locationUpdate);
        }
    }

    private void onClientDisconnected(ClientHandler client) {
        synchronized (connectedClients) {
            boolean wasRemoved = connectedClients.remove(client);
            if (wasRemoved) {
                Log.d(TAG, "Client removed: " + client.getClientAddress() +
                        ". Remaining clients: " + connectedClients.size());

                // Stop location updates when no clients connected
                if (connectedClients.isEmpty()) {
                    Log.d(TAG, "No clients remaining, stopping location updates");
                    mainHandler.post(this::stopLocationUpdates);
                }
            } else {
                Log.d(TAG, "Client was already removed: " + client.getClientAddress());
            }
        }

        mainHandler.post(() -> updateNotification("Client disconnected"));
    }

    public static boolean isServiceRunning() {
        return running;
    }

    // Public methods for checking service state
    public static boolean isServiceEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_IS_SERVICE_ENABLED, false);
    }

    public static void setServiceEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_IS_SERVICE_ENABLED, enabled).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", MODE_PRIVATE);
    }

    // Notifications

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(getString(R.string.app_description));
        notificationManager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String content;
        synchronized (connectedClients) {
            if (connectedClients.isEmpty()) {
                content = getString(R.string.notification_no_clients);
            } else {
                content = String.format(
                        getString(R.string.notification_clients),
                        connectedClients.size()
                );
            }

            content += getString(R.string.notification_divider);

            if (isGnssActive) {
                content += String.format(
                        getString(R.string.notification_satellites),
                        satelliteCount
                );

                if (lastLocation != null) {
                    content += getString(R.string.notification_divider)
                            + String.format(getString(R.string.notification_age), (System.currentTimeMillis() - lastLocation.getTime()) / 1000.0);
                }
            } else {
                content += getString(R.string.notification_gnss_inactive);
            }
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(String.format(getString(R.string.notification_title), getString(R.string.app_name)))
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String reason) {
        Log.d(TAG, "Updating notification: " + reason);
        notificationManager.notify(NOTIFICATION_ID, createNotification());
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientAddress;
        private static final long HEARTBEAT_TIMEOUT = 2000;
        private static final byte HEARTBEAT_PACKET = 0x01; // Expected heartbeat packet
        private long lastHeartbeatTime;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.clientAddress = socket.getRemoteSocketAddress().toString();
            this.lastHeartbeatTime = System.currentTimeMillis();

            Log.i(TAG, "New client connected: " + clientAddress);
        }

        public String getClientAddress() {
            return clientAddress;
        }

        @Override
        public void run() {
            try {
                // Set socket timeout for heartbeat detection
                socket.setSoTimeout(1000); // timeout for reads

                // Keep connection alive and handle heartbeat packets
                byte[] buffer = new byte[1];
                while (!socket.isClosed()) {
                    try {
                        // Try to read heartbeat packet
                        int result = socket.getInputStream().read(buffer);
                        if (result == -1) {
                            // Client closed connection
                            Log.i(TAG, "Client closed connection: " + clientAddress);
                            break;
                        } else if (result > 0) {
                            // Received data from client
                            if (buffer[0] == HEARTBEAT_PACKET) {
                                // Valid heartbeat packet received
                                lastHeartbeatTime = System.currentTimeMillis();
                                Log.v(TAG, "Heartbeat received from: " + clientAddress);
                            } else {
                                Log.w(TAG, "Unknown packet received from client: " + buffer[0]);
                            }
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // Check if heartbeat timeout exceeded
                        long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
                        if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT) {
                            Log.w(TAG, "Heartbeat timeout for client: " + clientAddress +
                                    " (last heartbeat " + timeSinceLastHeartbeat + "ms ago)");
                            break;
                        }
                        // Continue loop - timeout is expected when no heartbeat is received
                    } catch (IOException e) {
                        // Client disconnected
                        Log.i(TAG, "Client disconnected: " + clientAddress + " - " + e.getMessage());
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in client handler for " + clientAddress, e);
            } finally {
                disconnect();
                onClientDisconnected(this);
            }
        }

        public void sendLocationUpdate(LocationProto.LocationUpdate locationUpdate) {
            if (socket.isClosed()) {
                return;
            }

            try {
                byte[] data = locationUpdate.toByteArray();
                // Send length first (4 bytes) then data
                socket.getOutputStream().write(intToBytes(data.length));
                socket.getOutputStream().write(data);
                socket.getOutputStream().flush();
            } catch (IOException e) {
                Log.e(TAG, "Error sending location update to client", e);
                disconnect();
            }
        }

        public void disconnect() {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }

        private byte[] intToBytes(int value) {
            return new byte[]{
                    (byte) (value >>> 24),
                    (byte) (value >>> 16),
                    (byte) (value >>> 8),
                    (byte) value
            };
        }
    }
}
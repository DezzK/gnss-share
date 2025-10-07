/*
 * Copyright © 2025 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.gnssshare.server;

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
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GnssStatus;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import dezz.gnssshare.proto.LocationProto;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GNSSServerService extends Service {
    private static final String TAG = "GNSSServerService";
    private static final String WAKELOCK_TAG = "GNSSServerService:WakeLockTag";
    private static final int PORT = 8887;
    private static final String CHANNEL_ID = "GNSSServerChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_IS_SERVICE_ENABLED = "isServiceEnabled";

    private static boolean running = false;

    private ServerSocket serverSocket;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;

    private final ArrayList<ClientHandler> connectedClients = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stopUpdatesToken = new Object();

    private LocationProto.ServerResponse lastServerResponse = LocationProto.ServerResponse.newBuilder()
            .setStatus("Uninitialized")
            .build();

    private int satelliteCount = 0;
    private boolean isGnssActive = false;

    @Override
    public void onCreate() {
        super.onCreate();

        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

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

        locationManager = null;
        locationListener = null;

        executor.shutdown();

        notificationManager.cancel(NOTIFICATION_ID);
        notificationManager = null;
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
                    if (!connectedClients.isEmpty() && lastServerResponse != null && !lastServerResponse.hasLocationUpdate()) {
                        mainHandler.post(() -> updateNotification("satellites status changed"));
                    }
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

                while (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        Log.d(TAG, "Client connected: " + clientSocket.getRemoteSocketAddress());

                        ClientHandler clientHandler = new ClientHandler(clientSocket);
                        synchronized (connectedClients) {
                            connectedClients.add(clientHandler);
                            // Start location updates when first client connects
                            if (connectedClients.size() == 1) {
                                mainHandler.post(this::startLocationUpdates);
                            }
                        }
                        executor.execute(clientHandler);

                        updateNotification("New client connected");
                    } catch (IOException e) {
                        if (serverSocket != null && !serverSocket.isClosed()) {
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
        Log.d(TAG, "Stopping server");
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
                serverSocket = null;
            }

            for (ClientHandler client : connectedClients) {
                client.disconnect();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error stopping server", e);
        }
    }

    @SuppressLint("WakelockTimeout")
    private void startLocationUpdates() {
        mainHandler.removeCallbacksAndMessages(stopUpdatesToken);

        try {
            Log.d(TAG, "Starting location updates...");

            lastServerResponse = LocationProto.ServerResponse.newBuilder()
                    .setStatus("Waiting for location...")
                    .build();

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000,
                    0,
                    locationListener
            );

            wakeLock.acquire();

            Log.d(TAG, "Location updates started");

            isGnssActive = true;

            updateNotification("Started location updates");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting location updates", e);
        }
    }

    private void stopLocationUpdates() {
        Log.d(TAG, "Stopping location updates...");

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
        }

        Log.d(TAG, "Location updates stopped");

        isGnssActive = false;
        lastServerResponse = LocationProto.ServerResponse.newBuilder()
                .setStatus("Stopped")
                .build();

        updateNotification("Stopped location updates");
    }

    private void handleLocationUpdate(Location location) {
        Log.d(TAG, String.format("Handling location update: %s", location));

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

        lastServerResponse = LocationProto.ServerResponse.newBuilder()
                .setLocationUpdate(builder.build())
                .build();

        updateNotification("Received location update");

        // Broadcast to all connected clients
        Log.d(TAG, "Broadcasting location to " + connectedClients.size() + " clients: " + location);
        executor.execute(() -> broadcastLocationUpdate(lastServerResponse));
    }

    private void broadcastLocationUpdate(LocationProto.ServerResponse serverResponse) {
        // Copy clients list to avoid concurrent modification
        ArrayList<ClientHandler> clients;
        synchronized (connectedClients) {
            clients = new ArrayList<>(connectedClients);
        }
        for (ClientHandler client : clients) {
            client.sendResponse(serverResponse);
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
                    mainHandler.postDelayed(this::stopLocationUpdates, stopUpdatesToken, 15000);
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

    private LocationProto.ServerResponse getLastServerResponse() {
        return lastServerResponse;
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
            Log.d(TAG, String.format("Clients connected: %d", connectedClients.size()));
            if (connectedClients.isEmpty()) {
                Log.d(TAG, "No clients connected");
                content = getString(R.string.notification_no_clients);
            } else {
                content = String.format(
                        getString(R.string.notification_clients),
                        connectedClients.size()
                );
            }
        }

        content += getString(R.string.notification_divider);

        if (isGnssActive) {
            content += String.format(
                    getString(R.string.notification_satellites),
                    satelliteCount
            );


            if (lastServerResponse.hasLocationUpdate()) {
                content += getString(R.string.notification_divider) + String.format(
                        getString(R.string.notification_age),
                        (System.currentTimeMillis() - lastServerResponse.getLocationUpdate().getTimestamp()) / 1000.0
                );
            }
        } else {
            content += getString(R.string.notification_gnss_inactive);
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
        if (notificationManager == null) {
            return;
        }
        Log.d(TAG, "Updating notification: " + reason);
        notificationManager.notify(NOTIFICATION_ID, createNotification());
    }

    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientAddress;
        private static final long HEARTBEAT_TIMEOUT = 3000;
        private static final byte HEARTBEAT_PACKET = 0x01; // Expected heartbeat packet
        private static final long RESPONSE_TIMING_REQUIREMENT = 1000;
        private long lastHeartbeatTime;
        private long lastResponseTime = 0;

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

                // Keep connection alive and handle request packets
                byte[] buffer = new byte[1];
                while (!socket.isClosed() && socket.isConnected()) {
                    try {
                        // Try to read request packet
                        int result = socket.getInputStream().read(buffer);
                        if (socket.isClosed()) {
                            Log.i(TAG, "Client closed connection: " + clientAddress);
                            break;
                        }
                        if (result > 0) {
                            // Received data from client
                            if (buffer[0] == HEARTBEAT_PACKET) {
                                // Valid heartbeat packet received
                                lastHeartbeatTime = System.currentTimeMillis();
                                Log.v(TAG, "Heartbeat received from: " + clientAddress);

                                // Send response if last response was sent more than RESPONSE_TIMING_REQUIREMENT ago
                                // so the client will be sure that the server is still alive
                                if (lastResponseTime < lastHeartbeatTime - RESPONSE_TIMING_REQUIREMENT) {
                                    sendResponse(getLastServerResponse());
                                }

                                continue;
                            } else {
                                Log.w(TAG, "Unknown packet received from client: " + buffer[0]);
                            }
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // Continue loop - timeout is expected when no heartbeat is received
                    } catch (IOException e) {
                        Log.i(TAG, "Client disconnected: " + clientAddress + " - " + e.getMessage());
                        break;
                    }

                    // Check if heartbeat timeout exceeded
                    long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
                    if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT) {
                        Log.w(TAG, "Heartbeat timeout for client: " + clientAddress +
                                " (last heartbeat " + timeSinceLastHeartbeat + "ms ago)");
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in client handler for " + clientAddress, e);
            } finally {
                disconnect();
            }
        }

        private void sendResponse(LocationProto.ServerResponse response) {
            if (socket == null || !socket.isConnected() || socket.isClosed()) {
                return;
            }
            try {
                byte[] data = response.toByteArray();
                // Send length first (4 bytes) then data
                OutputStream output = socket.getOutputStream();
                output.write(intToBytes(data.length));
                output.write(data);
                output.flush();

                lastResponseTime = System.currentTimeMillis();
            } catch (IOException e) {
                Log.w(TAG, "Error sending location update to client", e);
                disconnect();
            }
        }

        public void disconnect() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }

            onClientDisconnected(this);
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
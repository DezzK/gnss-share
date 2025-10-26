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

package dezz.gnssshare.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.gnssshare.proto.LocationProto;

public class GNSSClientService extends Service implements ConnectionManager.ConnectionListener {
    private static final String TAG = "GNSSClientService";
    private static final String CHANNEL_ID = "GNSSClientChannel";
    private static final int NOTIFICATION_ID = 1;

    private static GNSSClientService instance = null;

    private ConnectionManager connectionManager;
    private MockLocationManager mockLocationManager;
    private NotificationManager notificationManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean isReceivingUpdates = new AtomicBoolean(false);

    private Socket currentSocket;
    private Location lastReceivedLocation;
    private static long lastUpdateTime;

    public static boolean isServiceEnabled(Context context) {
        return Preferences.serviceEnabled(context);
    }

    public static boolean isServiceRunning() {
        return instance != null;
    }

    public static ConnectionManager.ConnectionState getConnectionState() {
        return instance != null && instance.connectionManager != null ? instance.connectionManager.getCurrentState() : ConnectionManager.ConnectionState.DISCONNECTED;
    }

    public static String getServerAddress() {
        return instance != null && instance.connectionManager != null ? instance.connectionManager.getServerAddress() : null;
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            connectionManager.onNetworkAvailable();
        }

        @Override
        public void onLost(@NonNull Network network) {
            connectionManager.onNetworkLost();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = getSystemService(NotificationManager.class);
        mockLocationManager = new MockLocationManager(this);
        connectionManager = new ConnectionManager(this, this);

        // Register WiFi state receiver
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);

        createNotificationChannel();

        startForeground(NOTIFICATION_ID, createNotification(false));

        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;

        super.onDestroy();

        if (connectionManager != null) {
            connectionManager.shutdown();
        }
        executor.shutdown();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    // ConnectionManager.ConnectionListener implementation
    @Override
    public void onConnectionStateChanged(ConnectionManager.ConnectionState state, String message, String serverAddress) {
        Log.d(TAG, "Connection state: " + state + " - " + message);

        // Update notification
        updateNotification();

        // Notify activity about connection status change
        sendBroadcast(new Intent("dezz.gnssshare.CONNECTION_CHANGED")
                .putExtra("state", state.toString())
                .putExtra("serverAddress", serverAddress));
    }

    @Override
    public void onConnectionEstablished(Socket socket, String serverAddress) {
        Log.i(TAG, "Connection established, starting location updates");

        this.currentSocket = socket;
        startReceivingLocationUpdates(serverAddress);
    }

    @Override
    public void onDisconnected() {
        Log.i(TAG, "Connection lost, stopping location updates");

        this.currentSocket = null;

        stopReceivingLocationUpdates();

        // Notify activity about disconnection
        sendBroadcast(new Intent("dezz.gnssshare.CONNECTION_CHANGED")
                .putExtra("state", ConnectionManager.ConnectionState.DISCONNECTED.toString()));
    }

    private void startReceivingLocationUpdates(String serverAddress) {
        if (isReceivingUpdates.get() || currentSocket == null) {
            return;
        }

        isReceivingUpdates.set(true);

        if (!MockLocationManager.isMockLocationEnabled(getContentResolver())) {
            Log.w(TAG, "Mock locations not enabled - please enable in Developer Options");
            broadcastMockLocationStatus(getString(R.string.mock_location_enable_message), true);
        }

        try {
            mockLocationManager.startMockLocationProvider();
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up mock location provider", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
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

                        if (!connectionManager.isConnected()) {
                            connectionManager.setState(ConnectionManager.ConnectionState.CONNECTED, "Received first server response", serverAddress);
                        }

                        if (response.hasLocationUpdate()) {
                            handleLocationUpdate(response);
                        } else {
                            Log.i(TAG, "Server status: " + response.getStatus());
                            // Broadcast satellite info to activity
                            Intent intent = new Intent("dezz.gnssshare.LOCATION_UPDATE");
                            intent.putExtra("satellites", response.getSatellites());
                            sendBroadcast(intent);
                        }
                    } catch (IOException e) {
                        if (currentSocket != null && !currentSocket.isClosed() && !currentSocket.isInputShutdown() && !currentSocket.isOutputShutdown()) {
                            Log.e(TAG, "Error receiving location update", e);
                        }
                        // Let ConnectionManager handle the reconnection
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error in location update receiver", e);
            }

            connectionManager.disconnect("Connection lost - attempting to reconnect...");
            connectionManager.scheduleReconnect();
        });
    }

    private void stopReceivingLocationUpdates() {
        isReceivingUpdates.set(false);

        // Stop providing mock locations
        if (instance == null) {
            mockLocationManager.shutdown();
        } else {
            mockLocationManager.stopMockLocationProvider(5000);
        }
    }

    private int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }

    private void handleLocationUpdate(LocationProto.ServerResponse response) {
        try {
            LocationProto.LocationUpdate locationUpdate = response.getLocationUpdate();
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

            Log.i(TAG, "Received location update: " + location);

            // Update internal state
            lastReceivedLocation = location;
            lastUpdateTime = System.currentTimeMillis();

            // Update notification with new location data
            updateNotification();

            // Broadcast location update to activity
            Intent intent = new Intent("dezz.gnssshare.LOCATION_UPDATE");
            intent.putExtra("location", location);
            intent.putExtra("satellites", response.getSatellites());
            intent.putExtra("provider", locationUpdate.getProvider());
            intent.putExtra("locationAge", locationUpdate.getLocationAge());
            sendBroadcast(intent);

            // Set mock location
            mockLocationManager.setMockLocation(location);
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }
    }

    private void broadcastMockLocationStatus(String message, boolean error) {
        Intent intent = new Intent("dezz.gnssshare.MOCK_LOCATION_STATUS");
        intent.putExtra("message", message);
        intent.putExtra("error", error);
        sendBroadcast(intent);
    }

    public static long getLastUpdateTime() {
        return lastUpdateTime;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
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
}

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

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionManager {
    private static final String TAG = "ConnectionManager";
    private static final int SERVER_PORT = 8887;
    private static final long RECONNECT_DELAY = 500;
    private static final long CONNECTION_CHECK_INTERVAL = 1000;
    private static final long HEARTBEAT_INTERVAL = 1000; // Send heartbeat every second
    private static final byte HEARTBEAT_PACKET = 0x01; // Simple heartbeat packet

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    public interface ConnectionListener {
        void onConnectionStateChanged(ConnectionState state, String message, String serverAddress);

        void onConnectionEstablished(Socket socket, String serverAddress);

        void onDisconnected();
    }

    private final ConnectionListener listener;
    private final Context context;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ConnectionState currentState = ConnectionState.DISCONNECTED;
    private String gatewayIP = null;
    private String serverAddress = null;
    private Socket socket;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean isNetworkAvailable = new AtomicBoolean(false);

    private final Handler connectionCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable connectionCheckRunnable;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable;

    private final Handler gatewayIpGetHandler = new Handler(Looper.getMainLooper());
    private final Runnable gatewayIpGetRunnable;

    public ConnectionManager(Context context, ConnectionListener listener) {
        this.context = context;
        this.listener = listener;

        this.heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState != ConnectionState.DISCONNECTED && socket != null) {
                    sendHeartbeat();
                    heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL);
                }
            }
        };

        this.connectionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState == ConnectionState.CONNECTED) {
                    checkConnectionHealth();
                    connectionCheckHandler.postDelayed(this, CONNECTION_CHECK_INTERVAL);
                }
            }
        };

        this.gatewayIpGetRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState == ConnectionState.CONNECTING) {
                    findGatewayIP();
                    if (gatewayIP == null) {
                        gatewayIpGetHandler.postDelayed(this, CONNECTION_CHECK_INTERVAL);
                    } else {
                        // Now we know server address, let's update it in UI
                        setState(ConnectionState.CONNECTING, "Attempting to connect to server...", gatewayIP);
                        serverAddress = gatewayIP;
                        doConnect();
                    }
                }
            }
        };
    }

    private void sendHeartbeat() {
        executor.execute(() -> {
            try {
                // Send a simple heartbeat packet (1 byte)
                socket.getOutputStream().write(HEARTBEAT_PACKET);
                socket.getOutputStream().flush();
                Log.v(TAG, "Heartbeat sent");
            } catch (IOException e) {
                Log.w(TAG, "Failed to send heartbeat", e);
                mainHandler.post(this::handleConnectionLoss);
            }
        });
    }

    private void checkConnectionHealth() {
        executor.execute(() -> {
            try {
                if (socket == null || socket.isClosed() || !socket.isConnected() ||
                        socket.isInputShutdown() || socket.isOutputShutdown()) {
                    Log.w(TAG, "Socket connection lost");
                    mainHandler.post(this::handleConnectionLoss);
                }
            } catch (Exception e) {
                Log.w(TAG, "Connection health check failed", e);
                mainHandler.post(this::handleConnectionLoss);
            }
        });
    }

    public void onNetworkAvailable() {
        Log.d(TAG, "Network available");
        isNetworkAvailable.set(true);
        gatewayIP = null;
        if (!shutdown.get() && currentState == ConnectionState.DISCONNECTED) {
            connect();
        }
    }

    public void onNetworkLost() {
        Log.d(TAG, "Network lost");
        gatewayIP = null;
        isNetworkAvailable.set(false);
        if (!shutdown.get() && currentState != ConnectionState.DISCONNECTED) {
            disconnect("WiFi disconnected");
        }
    }

    public void connect() {
        if (shutdown.get()) {
            return;
        }

        if (currentState == ConnectionState.CONNECTED) {
            return;
        }

        boolean useGatewayIp = Preferences.useGatewayIp(context);
        if (useGatewayIp) {
            serverAddress = gatewayIP;
        } else {
            serverAddress = Preferences.serverAddress(context);
        }

        setState(ConnectionState.CONNECTING, "Attempting to connect to server...", serverAddress);

        if (useGatewayIp && serverAddress == null) {
            gatewayIpGetHandler.post(gatewayIpGetRunnable);
        } else {
            doConnect();
        }
    }

    private void doConnect() {
        executor.execute(() -> {
            try {
                Log.i(TAG, "Connecting to " + serverAddress + ":" + SERVER_PORT);
                socket = new Socket();
                socket.connect(new InetSocketAddress(serverAddress, SERVER_PORT), 500);
                socket.setSoTimeout(2000);

                if (shutdown.get()) {
                    // Connection no longer wanted
                    try {
                        socket.close();
                    } catch (IOException e) {
                        Log.w(TAG, "Error closing unwanted socket", e);
                    }
                    return;
                }
            } catch (IOException e) {
                Log.w(TAG, "Connection failed: " + e.getMessage());
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ex) {
                        Log.w(TAG, "Error closing socket", ex);
                    }
                    socket = null;
                }
                scheduleReconnect();
                return;
            }

            connectionCheckHandler.post(connectionCheckRunnable);
            heartbeatHandler.post(heartbeatRunnable);
            mainHandler.post(() -> listener.onConnectionEstablished(socket, serverAddress));
        });
    }

    public void disconnect(String message) {
        setState(ConnectionState.DISCONNECTED, message, null);

        connectionCheckHandler.removeCallbacks(connectionCheckRunnable);
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        mainHandler.removeCallbacksAndMessages(null);

        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing socket", e);
            }
            socket = null;
        }

        listener.onDisconnected();
    }

    public ConnectionState getCurrentState() {
        return currentState;
    }

    public boolean isConnected() {
        return currentState == ConnectionState.CONNECTED;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    void setState(ConnectionState newState, String message, String serverAddress) {
        if (currentState != newState || !Objects.equals(this.serverAddress, serverAddress)) {
            Log.d(TAG, "State change: " + currentState + " -> " + newState + " (" + message + ")");
            currentState = newState;
            listener.onConnectionStateChanged(newState, message, serverAddress);
        }
    }

    private void findGatewayIP() {
        gatewayIP = getGatewayIpAddress(context);
        if (gatewayIP == null) {
            Log.w(TAG, "Can't get gateway IP address");
        } else {
            Log.d(TAG, "Gateway IP: " + gatewayIP);
        }
    }

    private void handleConnectionLoss() {
        if (shutdown.get()) {
            return;
        }

        if (currentState == ConnectionState.CONNECTED) {
            disconnect("Connection lost - attempting to reconnect...");
            scheduleReconnect();
        }
    }

    public void scheduleReconnect() {
        if (shutdown.get() || !this.isNetworkAvailable.get()) {
            return;
        }

        long delay = RECONNECT_DELAY;
        mainHandler.postDelayed(() -> {
            if (!shutdown.get() && this.isNetworkAvailable.get()) {
                Log.i(TAG, "Scheduling reconnection attempt in " + delay + "ms");
                connect();
            }
        }, delay);
    }

    public void shutdown() {
        shutdown.set(true);
        disconnect("Shutting down");
        executor.shutdown();
    }

    private static String getGatewayIpAddress(Context context) {
        WifiManager wifiManager = context.getSystemService(WifiManager.class);
        if (wifiManager != null) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo != null) {
                int gatewayIp = dhcpInfo.gateway;
                return intToIp(gatewayIp);
            }
        }
        return null;
    }

    private static String intToIp(int i) {
        return (i & 0xFF) + "." +
                ((i >> 8) & 0xFF) + "." +
                ((i >> 16) & 0xFF) + "." +
                ((i >> 24) & 0xFF);
    }
}

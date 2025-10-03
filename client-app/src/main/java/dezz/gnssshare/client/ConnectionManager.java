package dezz.gnssshare.client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionManager {
    private static final String TAG = "ConnectionManager";
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
        void onConnectionStateChanged(ConnectionState state, String message);

        void onConnectionEstablished(Socket socket);

        void onConnectionLost();
    }

    private final String serverIP;
    private final int serverPort;
    private final ConnectionListener listener;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ConnectionState currentState = ConnectionState.DISCONNECTED;
    private Socket socket;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean isNetworkAvailable = new AtomicBoolean(false);

    private final Handler connectionCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable connectionCheckRunnable;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable;

    public ConnectionManager(String serverIP, int serverPort, ConnectionListener listener) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        this.listener = listener;

        this.heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState == ConnectionState.CONNECTED && socket != null) {
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
    }

    private void sendHeartbeat() {
        executor.execute(() -> {
            try {
                // Send a simple heartbeat packet (1 byte)
                socket.getOutputStream().write(HEARTBEAT_PACKET);
                socket.getOutputStream().flush();
                Log.v(TAG, "Request sent");
            } catch (IOException e) {
                Log.w(TAG, "Failed to send request", e);
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
        if (!shutdown.get() && currentState == ConnectionState.DISCONNECTED) {
            connect();
        }
    }

    public void onNetworkLost() {
        Log.d(TAG, "Network lost");
        isNetworkAvailable.set(false);
        if (!shutdown.get() && currentState != ConnectionState.DISCONNECTED) {
            setState(ConnectionState.DISCONNECTED, "WiFi disconnected");
            disconnect();
        }
    }

    public void connect() {
        if (shutdown.get()) {
            return;
        }

        if (currentState == ConnectionState.CONNECTING) {
            return; // Already attempting connection
        }

        setState(ConnectionState.CONNECTING, "Attempting to connect to server...");

        executor.execute(() -> {
            try {
                Log.i(TAG, "Connecting to " + serverIP + ":" + serverPort);
                socket = new Socket();
                socket.connect(new InetSocketAddress(serverIP, serverPort), 1000);
                socket.setSoTimeout(2000);

                mainHandler.post(() -> {
                    if (shutdown.get()) {
                        // Connection no longer wanted
                        try {
                            socket.close();
                        } catch (IOException e) {
                            Log.w(TAG, "Error closing unwanted socket", e);
                        }
                        return;
                    }

                    setState(ConnectionState.CONNECTED, "Connected to GNSS server");
                    connectionCheckHandler.post(connectionCheckRunnable);
                    heartbeatHandler.post(heartbeatRunnable);
                    listener.onConnectionEstablished(socket);
                });

            } catch (IOException e) {
                Log.w(TAG, "Connection failed: " + e.getMessage());

                mainHandler.post(() -> {
                    setState(ConnectionState.DISCONNECTED, "Connection failed");
                    if (!shutdown.get()) {
                        scheduleReconnect();
                    }
                });
            }
        });
    }

    public void disconnect() {
        setState(ConnectionState.DISCONNECTED, "Disconnecting...");

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

        listener.onConnectionLost();
    }

    public boolean isConnected() {
        return currentState == ConnectionState.CONNECTED;
    }

    private void setState(ConnectionState newState, String message) {
        if (currentState != newState) {
            Log.d(TAG, "State change: " + currentState + " -> " + newState + " (" + message + ")");
            currentState = newState;
            listener.onConnectionStateChanged(newState, message);
        }
    }

    private void handleConnectionLoss() {
        if (shutdown.get()) {
            return;
        }

        if (currentState == ConnectionState.CONNECTED) {
            setState(ConnectionState.DISCONNECTED, "Connection lost - attempting to reconnect...");
            disconnect();
            listener.onConnectionLost();
            scheduleReconnect();
        }
    }

    public void scheduleReconnect() {
        if (shutdown.get() || !this.isNetworkAvailable.get()) {
            return;
        }

        long delay = RECONNECT_DELAY;
        Log.i(TAG, "Scheduling reconnection attempt in " + delay + "ms");
        mainHandler.postDelayed(() -> {
            if (!shutdown.get() && this.isNetworkAvailable.get()) {
                connect();
            }
        }, delay);
    }

    public void shutdown() {
        shutdown.set(true);
        disconnect();
        executor.shutdown();
    }
}

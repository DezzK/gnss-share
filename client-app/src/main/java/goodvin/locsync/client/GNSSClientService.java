/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
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

package goodvin.locsync.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import goodvin.locsync.shared.AppLog;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import goodvin.locsync.proto.LocationProto;
import goodvin.locsync.shared.AndroidSystemStatsReader;
import goodvin.locsync.shared.Metrics;
import goodvin.locsync.shared.MetricsCsvWriter;
import goodvin.locsync.shared.MetricsSnapshot;
import goodvin.locsync.shared.Protocol;
import goodvin.locsync.shared.SystemStatsReader;

public class GNSSClientService extends Service implements ConnectionManager.ConnectionListener {
    private static final String TAG = "GNSSClientService";
    private static final String CHANNEL_ID = "GNSSClientChannel";
    private static final int NOTIFICATION_ID = 1;

    private static GNSSClientService instance = null;

    private ConnectionManager connectionManager;
    private MockLocationManager mockLocationManager;
    private NotificationManager notificationManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private Location lastReceivedLocation;
    private static long lastUpdateTime;
    // The phone's battery and heat from the last response (null until a server that sends it).
    private record PhoneSample(LocationProto.PhoneState state, long elapsedMs) {}
    private static volatile PhoneSample phoneSample;
    private int lastBroadcastSatelliteCount = -1;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastResponseTime = 0;     // elapsedRealtime of the last RESPONSE

    private final LocationKalmanFilter kalman = new LocationKalmanFilter(2.0, 1.0);
    private static final long OUTPUT_INTERVAL_MS = 100;   // 10 Hz
    private static final long GPS_LOSS_CAP_MS = 2500;           // at the usual 1 fix/s
    // The server's update interval is configurable (up to 5 s), so "GPS lost" and the prediction
    // horizon scale with the observed fix interval; a fixed 2.5 s froze the icon between slow fixes.
    private static final double GPS_LOSS_INTERVALS = 2.5;
    private static final long MAX_GPS_LOSS_CAP_MS = 12_500;
    private double fixIntervalEmaMs = 1000;
    private volatile long lastFixElapsedMs = 0;           // SystemClock.elapsedRealtime of last real fix
    // Head-unit elapsedRealtime the filter state refers to: the fix time, i.e. arrival minus the
    // fix's age when latency compensation is on. Output ticks extrapolate from here to "now".
    private long stateElapsedMs = 0;
    private static final long MAX_LATENCY_MS = 3000;        // ignore absurd ages (clock glitches)
    private static final double MAX_HORIZON_S = 3.0;        // never extrapolate further than this
    private long lastFedFixTimestampMs = Long.MIN_VALUE;  // LocationUpdate.timestamp last fed to the filter (dedup keepalive resends)
    private volatile double lastAltitude = 0;
    // Last vertical accuracy / MSL altitude received, replayed onto the smoothed output (the Kalman
    // filter is horizontal-only, so these pass through unchanged). NaN = not reported.
    private volatile float lastVerticalAcc = Float.NaN;
    private volatile double lastMslAltitude = Double.NaN;
    private volatile float lastMslAltitudeAcc = Float.NaN;
    private FilterConfig filterConfig = new FilterConfig();  // re-read from Preferences on every fix
    // Last injected output, for the Monitor screen's filter card (NaN = nothing injected yet).
    private double outAccuracy = Double.NaN, outSpeedAcc = Double.NaN, outBearingAcc = Double.NaN;
    private double lastLatencyMs = Double.NaN, lastHorizonMs = Double.NaN, lastNetworkDelayMs = Double.NaN;
    private final FixClock fixClock = new FixClock();
    private static final double MOVING_SHOW_FACTOR = 1.6, MOVING_HIDE_FACTOR = 0.8; // × standstill speed
    private boolean outputMoving = false;
    private long nextTickUptimeMs = 0;              // fixed 10 Hz grid for output ticks
    private static final long NOTIFICATION_MIN_INTERVAL_MS = 5000;
    private long lastNotificationElapsedMs = 0;
    private android.net.wifi.WifiManager.WifiLock wifiLockLowLatency, wifiLockHighPerf;
    // Monitor-only diagnostics, independent of what the "Honest accuracy" setting injects: the
    // filter's own speed/bearing accuracy, and what the phone reported for the last fix.
    private double filtSpeedAcc = Double.NaN, filtBearingAcc = Double.NaN;
    private double inSpeedAcc = Double.NaN, inBearingAcc = Double.NaN;
    private TrackRecorder trackRecorder;    // non-null while "Record track" is on
    private final Runnable smoothingTick = this::smoothingTick;

    private volatile DatagramSocket udpSocket;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Metrics metrics = new Metrics();
    private SystemStatsReader statsReader;
    private MetricsCsvWriter csvWriter;
    private volatile boolean metricsPrimed = false;
    private long lastTickWallMs = 0;
    private static final long METRICS_INTERVAL_MS = 1000;
    private static final String METRICS_TAG = "METRICS";
    private final Runnable metricsTick = this::sampleMetrics;
    private final java.text.SimpleDateFormat metricsTs =
            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
    private static final long HELLO_INTERVAL_MS = 1000;
    private static final long CONNECTED_TIMEOUT_MS = 3000;
    private static final String BROADCAST_ADDR = "255.255.255.255";
    // Fallback for head-unit Wi-Fi stacks that drop limited broadcast: sweep the /24, at most this
    // often, and only on subnets no wider than /24 so we never blast thousands of hosts.
    private static final long SWEEP_MIN_INTERVAL_MS = 30_000;
    private static final int SWEEP_PREFIX = 24;   // only sweep a /24; narrower subnets are small, broadcast suffices
    private final Runnable helloTick = this::sendHelloTick;
    private static final byte[] HELLO_PACKET = Protocol.buildPacket(Protocol.TYPE_HELLO, null); // never changes
    private long lastSweepElapsed = 0;
    // Discovery diagnostics: count ticks spent searching and log one actionable line now and then.
    private int searchingTicks = 0;
    private static final int SEARCH_LOG_EVERY = 10;   // ~10 s at a 1 Hz tick

    private static final String WIDGET_SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    private static final String WIDGET_PACKAGE = "dezz.status.widget";

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
            startTransport();
        }

        @Override
        public void onLost(@NonNull Network network) {
            stopTransport("WiFi disconnected");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        AppLog.setDebug(Preferences.debugLoggingEnabled(this));
        notificationManager = getSystemService(NotificationManager.class);
        mockLocationManager = new MockLocationManager(this);
        connectionManager = new ConnectionManager(this, this);

        registerWiFiStateReceiver();
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, createNotification(false));
        }

        instance = this;
    }

    private void registerWiFiStateReceiver() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;

        super.onDestroy();

        stopTransport("Service destroyed");
        executor.shutdown();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    // ConnectionManager.ConnectionListener implementation
    @Override
    public void onConnectionStateChanged(ConnectionManager.ConnectionState state, String message, String serverAddress) {
        AppLog.d(TAG, "Connection state: " + state + " - " + message);

        updateNotification();

        // Notify activity about connection status change
        sendBroadcast(new Intent("goodvin.locsync.CONNECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("state", state.toString())
                .putExtra("serverAddress", serverAddress));
    }

    private void startTransport() {
        if (running.getAndSet(true)) {
            return;
        }

        DatagramSocket sock;
        try {
            sock = new DatagramSocket();
        } catch (IOException e) {
            Log.e(TAG, "Failed to open UDP socket", e);
            running.set(false);   // allow a later onAvailable() to retry
            return;
        }
        udpSocket = sock;
        try {
            sock.setBroadcast(true);
        } catch (SocketException e) {
            Log.w(TAG, "Failed to enable broadcast on socket", e);
        }
        if (!running.get()) {
            // stopTransport() raced in during socket open — close and bail
            AppLog.i(TAG, "Transport stopped during socket open; closing");
            sock.close();
            udpSocket = null;
            return;
        }

        String server = connectionManager.getSendTarget();
        connectionManager.setState(ConnectionManager.ConnectionState.CONNECTING, "Connecting to server...", server);

        if (!MockLocationManager.isMockLocationEnabled(getContentResolver())) {
            Log.w(TAG, "Mock locations not enabled - please enable in Developer Options");
            broadcastMockLocationStatus(getString(R.string.mock_location_enable_message), true);
        }
        try {
            mockLocationManager.startMockLocationProvider();
        } catch (SecurityException e) {
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }

        executor.execute(this::receiveLoop);
        mainHandler.post(helloTick);

        stateElapsedMs = 0;
        filterConfig = Preferences.filterConfig(this);
        if (filterConfig.wifiLowLatency) {
            acquireWifiLocks();
        }
        nextTickUptimeMs = 0;
        outputMoving = false;
        mainHandler.post(smoothingTick);

        lastTickWallMs = 0;
        mainHandler.post(this::startMetricsSampler);
    }

    private void stopTransport(String reason) {
        if (!running.getAndSet(false)) {
            return;
        }
        AppLog.i(TAG, "Stopping transport: " + reason);
        mainHandler.removeCallbacks(helloTick);
        mainHandler.removeCallbacks(smoothingTick);
        mainHandler.removeCallbacks(metricsTick);
        metricsPrimed = false;
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
        lastFixElapsedMs = 0;
        stateElapsedMs = 0;
        kalman.reset();
        fixClock.reset();
        fixIntervalEmaMs = 1000;
        releaseWifiLocks();
        outAccuracy = outSpeedAcc = outBearingAcc = Double.NaN;
        filtSpeedAcc = filtBearingAcc = inSpeedAcc = inBearingAcc = Double.NaN;
        if (trackRecorder != null) {
            trackRecorder.close();
            trackRecorder = null;
        }
        lastLatencyMs = lastHorizonMs = Double.NaN;
        connectionManager.clearLearnedServerAddress();
        lastBroadcastSatelliteCount = -1;
        broadcastSatelliteStatusToWidget(0);

        if (instance == null) {
            mockLocationManager.shutdown();
        } else {
            mockLocationManager.stopMockLocationProvider(5000);
        }

        connectionManager.setState(ConnectionManager.ConnectionState.DISCONNECTED, reason, null);
        sendBroadcast(new Intent("goodvin.locsync.CONNECTION_CHANGED")
                .setPackage(getPackageName())
                .putExtra("state", ConnectionManager.ConnectionState.DISCONNECTED.toString()));
    }

    private void sendHelloTick() {
        if (!running.get()) {
            return;
        }
        String target = connectionManager.getSendTarget(); // null in Auto mode until discovered
        // Build the target list once (buildProbeTargets() reads DhcpInfo and throttles the sweep).
        final List<String> targets = (target != null)
                ? Collections.singletonList(target)   // known server (fixed/learned/seeded): unicast
                : buildProbeTargets();                // auto, not yet found: broadcast + optional sweep
        final DatagramSocket sock = udpSocket;
        if (sock != null) {
            executor.execute(() -> sendProbes(sock, targets));
        }
        // Discovery diagnostics: while we have no server yet, log what we're probing so a stuck
        // "searching" is actionable (hostile hotspot isolation, wrong network, etc.).
        if (target == null) {
            if (searchingTicks++ % SEARCH_LOG_EVERY == 0) {
                // A sweep tick carries ~256 hosts; log the count then, not the whole list.
                String what = targets.size() > 8 ? (targets.size() + " targets (broadcast + /24 sweep)")
                        : targets.toString();
                AppLog.i(TAG, "Discovery: no server after " + searchingTicks + " tick(s); probing " + what);
            }
        } else {
            searchingTicks = 0;
        }
        // Recency check: drop to CONNECTING if no RESPONSE within the window; in Auto mode also
        // forget the learned server so the next ticks broadcast to re-discover.
        if (connectionManager.isConnected()
                && SystemClock.elapsedRealtime() - lastResponseTime > CONNECTED_TIMEOUT_MS) {
            connectionManager.setState(ConnectionManager.ConnectionState.CONNECTING,
                    "Waiting for server...", connectionManager.getServerAddress());
            if (connectionManager.isAutoDiscover()) {
                connectionManager.clearLearnedServerAddress();
            }
        }
        mainHandler.postDelayed(helloTick, HELLO_INTERVAL_MS);
    }

    /**
     * Where to send discovery HELLOs when no server is known yet: the limited broadcast, plus the
     * subnet-directed broadcast (some head-unit Wi-Fi drivers drop 255.255.255.255 but pass the
     * directed one), plus a throttled per-host /24 sweep as a last resort.
     */
    private List<String> buildProbeTargets() {
        List<String> targets = new ArrayList<>();
        targets.add(BROADCAST_ADDR);
        // Probe last run's server directly — instant reconnect when it hasn't moved.
        String seed = connectionManager.getSeedAddress();
        if (seed != null) {
            targets.add(seed);
        }

        DhcpInfo dhcp = null;
        android.net.wifi.WifiManager wm =
                getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        if (wm != null) {
            try {
                dhcp = wm.getDhcpInfo();
            } catch (RuntimeException e) {
                AppLog.v(TAG, "getDhcpInfo failed: " + e.getMessage());
            }
        }
        if (dhcp == null || dhcp.ipAddress == 0) {
            return targets;
        }
        int ip = dhcp.ipAddress;      // little-endian per DhcpInfo
        int mask = dhcp.netmask;
        if (mask != 0) {
            // Subnet-directed broadcast = host bits all ones.
            int directed = (ip & mask) | ~mask;
            targets.add(intToIp(directed));
        }
        // Bounded unicast sweep: only /24-or-narrower, at most once per SWEEP_MIN_INTERVAL_MS.
        long nowElapsed = SystemClock.elapsedRealtime();
        int prefix = (mask == 0) ? 0 : Integer.bitCount(mask);
        if (prefix == SWEEP_PREFIX && nowElapsed - lastSweepElapsed >= SWEEP_MIN_INTERVAL_MS) {
            lastSweepElapsed = nowElapsed;
            int base = ip & 0x00FFFFFF;   // keep low 3 octets (little-endian: top octet is host)
            for (int host = 1; host <= 254; host++) {
                int candidate = base | (host << 24);
                if (candidate != ip) {
                    targets.add(intToIp(candidate));
                }
            }
        }
        return targets;
    }

    private void sendProbes(DatagramSocket sock, List<String> targets) {
        for (String dest : targets) {
            try {
                sock.send(new DatagramPacket(HELLO_PACKET, HELLO_PACKET.length,
                        InetAddress.getByName(dest), Protocol.PORT));
                metrics.recordPacketSent(HELLO_PACKET.length);
            } catch (IOException e) {
                Log.w(TAG, "Failed to send HELLO to " + dest, e);
            }
        }
    }

    /** Format a little-endian DhcpInfo int as a dotted IPv4 string. */
    private static String intToIp(int addr) {
        return (addr & 0xFF) + "." + ((addr >> 8) & 0xFF) + "."
                + ((addr >> 16) & 0xFF) + "." + ((addr >> 24) & 0xFF);
    }

    private void receiveLoop() {
        DatagramSocket sock = udpSocket;
        if (sock == null) {
            return;
        }
        byte[] buffer = new byte[Protocol.MAX_PACKET_BYTES];
        while (running.get() && !sock.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                sock.receive(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (running.get() && !sock.isClosed()) {
                    AppLog.v(TAG, "UDP receive error: " + e.getMessage());
                    try {
                        Thread.sleep(200);   // avoid hot-spin on repeated errors
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        Protocol.Header header;
        try {
            header = Protocol.parse(packet.getData(), packet.getLength());
        } catch (IllegalArgumentException e) {
            return;
        }

        if (header.type == Protocol.TYPE_VERSION_MISMATCH
                || !Protocol.isSupportedVersion(header.version)) {
            broadcastMockLocationStatus(getString(R.string.version_mismatch), true);
            return;
        }
        if (header.type != Protocol.TYPE_RESPONSE) {
            return;
        }
        metrics.recordPacketRecv(packet.getLength(), SystemClock.elapsedRealtime());

        try {
            // Parse in place from the receive buffer (no copy); handled synchronously before the
            // buffer is reused for the next datagram.
            LocationProto.ServerResponse response = LocationProto.ServerResponse.parseFrom(
                    java.nio.ByteBuffer.wrap(packet.getData(), header.payloadOffset, header.payloadLength));

            lastResponseTime = SystemClock.elapsedRealtime();   // monotonic, immune to clock changes
            String srcAddr = packet.getAddress().getHostAddress();
            if (connectionManager.isAutoDiscover()) {
                connectionManager.setLearnedServerAddress(srcAddr);
            }
            if (!connectionManager.isConnected()) {
                connectionManager.setState(ConnectionManager.ConnectionState.CONNECTED,
                        "Receiving from server", srcAddr);
            }

            if (response.hasPhone()) {
                phoneSample = new PhoneSample(response.getPhone(), SystemClock.elapsedRealtime());
            }
            if (response.hasLocationUpdate()) {
                handleLocationUpdate(response);
            } else {
                AppLog.i(TAG, "Server status: " + response.getStatus());
                Intent intent = new Intent("goodvin.locsync.LOCATION_UPDATE");
                intent.setPackage(getPackageName());
                intent.putExtra("satellites", response.getSatellites());
                sendBroadcast(intent);
            }
            broadcastSatelliteStatusToWidget(response.getSatellites());
        } catch (IOException e) {
            Log.w(TAG, "Failed to parse ServerResponse", e);
        }
    }

    /** Copy vertical accuracy / MSL altitude from an incoming update onto a Location (direct path). */
    private void applyVerticalAccuracy(Location loc, LocationProto.LocationUpdate u) {
        if (u.hasVerticalAccuracy()) {
            loc.setVerticalAccuracyMeters(u.getVerticalAccuracy());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && u.hasMslAltitude()) {
            loc.setMslAltitudeMeters(u.getMslAltitude());
            if (u.hasMslAltitudeAccuracy()) {
                loc.setMslAltitudeAccuracyMeters(u.getMslAltitudeAccuracy());
            }
        }
    }

    /** Replay the last received vertical accuracy / MSL altitude onto the smoothed output. */
    private void applyVerticalAccuracyFiltered(Location loc) {
        if (!Float.isNaN(lastVerticalAcc)) {
            loc.setVerticalAccuracyMeters(lastVerticalAcc);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !Double.isNaN(lastMslAltitude)) {
            loc.setMslAltitudeMeters(lastMslAltitude);
            if (!Float.isNaN(lastMslAltitudeAcc)) {
                loc.setMslAltitudeAccuracyMeters(lastMslAltitudeAcc);
            }
        }
    }

    private void handleLocationUpdate(LocationProto.ServerResponse response) {
        try {
            LocationProto.LocationUpdate locationUpdate = response.getLocationUpdate();
            metrics.recordFixAgeMs(locationUpdate.getLocationAge() * 1000.0);
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
            applyVerticalAccuracy(location, locationUpdate);

            AppLog.i(TAG, "Received location update: " + location);

            // Update internal state
            lastReceivedLocation = location;
            lastUpdateTime = System.currentTimeMillis();

            // Update notification with new location data
            updateNotificationThrottled();

            // Broadcast location update to activity
            Intent intent = new Intent("goodvin.locsync.LOCATION_UPDATE");
            intent.putExtra("location", location);
            intent.putExtra("satellites", response.getSatellites());
            intent.putExtra("provider", locationUpdate.getProvider());
            intent.putExtra("locationAge", locationUpdate.getLocationAge());
            intent.setPackage(getPackageName());
            sendBroadcast(intent);

            // Feed the fix into the Kalman filter on the main thread; the smoothing loop
            // (also main-thread) reads/predicts the same filter, so all access is confined
            // to one thread and no synchronization is needed.
            final double lat = locationUpdate.getLatitude();
            final double lon = locationUpdate.getLongitude();
            final float spd = locationUpdate.getSpeed();
            final float brg = locationUpdate.getBearing();
            final float acc = locationUpdate.getAccuracy();
            final float spdAcc = locationUpdate.getSpeedAccuracy();
            final float brgAcc = locationUpdate.getBearingAccuracy();
            final boolean hasSpdAcc = locationUpdate.hasSpeedAccuracy();
            final boolean hasBrgAcc = locationUpdate.hasBearingAccuracy();
            final boolean hasSpd = locationUpdate.hasSpeed();
            final boolean hasBrg = locationUpdate.hasBearing();
            final long fixTs = locationUpdate.getTimestamp();
            final double alt = locationUpdate.getAltitude();
            final boolean hasVacc = locationUpdate.hasVerticalAccuracy();
            final float vacc = locationUpdate.getVerticalAccuracy();
            final boolean hasMsl = locationUpdate.hasMslAltitude();
            final double msl = locationUpdate.getMslAltitude();
            final boolean hasMslAcc = locationUpdate.hasMslAltitudeAccuracy();
            final float mslAcc = locationUpdate.getMslAltitudeAccuracy();
            final float ageS = locationUpdate.getLocationAge();
            final String provider = locationUpdate.getProvider();
            final int sats = response.getSatellites();
            // Taken on the receive thread, before the hop to the main thread, so queueing there
            // doesn't count as network delay.
            final long arrivalElapsed = SystemClock.elapsedRealtime();
            mainHandler.post(() -> {
                try {
                    // Skip keepalive resends of a fix already fed to the filter. The server re-sends the
                    // last response ~1 Hz to keep the connection live; re-feeding that same stale fix (and,
                    // in a tunnel, its phantom speed=0) is what pinned velocity and froze the icon.
                    // Older than what we already have (a delayed packet overtaken by a newer one): the
                    // filter has moved past it, feeding it would step the state backwards.
                    if (fixTs <= lastFedFixTimestampMs) {
                        return;
                    }
                    long prevFixTs = lastFedFixTimestampMs;
                    lastFedFixTimestampMs = fixTs;
                    metrics.recordFix();
                    inSpeedAcc = hasSpdAcc ? spdAcc : Double.NaN;
                    inBearingAcc = hasBrgAcc ? brgAcc : Double.NaN;
                    filterConfig = Preferences.filterConfig(this);
                    kalman.setTurnModel(filterConfig.turnModel);
                    kalman.setTurnResponsiveness(filterConfig.turnResponsiveness);
                    kalman.setBearingHandling(filterConfig.bearingCompensation, filterConfig.minBearingAccuracyDeg);
                    kalman.setAdaptivePosition(filterConfig.adaptivePosition);
                    kalman.setGating(filterConfig.gating, filterConfig.gateThreshold);
                    kalman.setStandstill(filterConfig.standstillHold, filterConfig.standstillSpeed);
                    kalman.setProcessNoise(filterConfig.processNoise, filterConfig.adaptiveNoise);

                    long nowElapsed = arrivalElapsed;
                    // When the fix was taken, on our clock: its age on arrival is the phone-side age
                    // plus Wi-Fi delivery delay (FixClock), plus any user-configured extra latency.
                    long ageMs = Math.round(Math.max(0, ageS) * 1000);
                    long arrivalLatencyMs = filterConfig.networkDelayCompensation
                            ? fixClock.latencyMs(arrivalElapsed, fixTs, ageMs) : ageMs;
                    lastNetworkDelayMs = filterConfig.networkDelayCompensation
                            ? fixClock.lastExtraDelayMs() : Double.NaN;
                    long latencyMs = 0;
                    if (filterConfig.latencyCompensation) {
                        latencyMs = Math.round(arrivalLatencyMs + filterConfig.extraLatencyMs);
                        latencyMs = Math.max(0, Math.min(latencyMs, MAX_LATENCY_MS));
                    }
                    long measElapsed = nowElapsed - latencyMs;
                    lastLatencyMs = latencyMs;

                    boolean resumingAfterGap =
                            lastFixElapsedMs == 0 || (nowElapsed - lastFixElapsedMs) > gpsLossCapMs();
                    long fixDt = fixTs - prevFixTs;
                    if (!resumingAfterGap && prevFixTs != Long.MIN_VALUE && fixDt > 0 && fixDt <= MAX_GPS_LOSS_CAP_MS) {
                        fixIntervalEmaMs += 0.2 * (fixDt - fixIntervalEmaMs);   // gaps don't count
                    }
                    if (resumingAfterGap) {
                        // Fresh start, or the first real fix after a GPS gap (e.g. tunnel exit): re-anchor so
                        // the estimate snaps to the new position instead of lurching from stale state.
                        kalman.reset();
                    } else if (kalman.isInitialized() && stateElapsedMs > 0) {
                        // Step the state from the previous fix time to this one. The phone's fix timestamps
                        // give the exact interval; fall back to our clock if they look wrong. Cap dt so a
                        // long GPS gap can't feed a huge predict step (bounds process-noise growth).
                        double dt = (fixTs - prevFixTs) / 1000.0;
                        if (!(dt > 0 && dt <= 5.0)) {
                            dt = (measElapsed - stateElapsedMs) / 1000.0;
                        }
                        kalman.predict(Math.max(0, Math.min(dt, 5.0)));
                    }
                    kalman.update(lat, lon, spd, brg, acc, spdAcc, brgAcc, hasSpd, hasBrg);
                    lastAltitude = alt;
                    lastVerticalAcc = hasVacc ? vacc : Float.NaN;
                    lastMslAltitude = hasMsl ? msl : Double.NaN;
                    lastMslAltitudeAcc = hasMslAcc ? mslAcc : Float.NaN;
                    lastFixElapsedMs = nowElapsed;
                    stateElapsedMs = measElapsed;

                    updateTrackRecorder();
                    if (trackRecorder != null) {
                        trackRecorder.fix(nowElapsed, fixTs, provider, sats, lat, lon, alt, acc, hasSpd, spd, hasBrg, brg,
                                spdAcc, brgAcc, ageS, latencyMs, kalman);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error updating Kalman filter", e);
                }
            });
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }
    }

    private void smoothingTick() {
        long tickWall = SystemClock.elapsedRealtime();
        if (lastTickWallMs != 0) {
            metrics.recordTickJitterMs(Math.abs((tickWall - lastTickWallMs) - OUTPUT_INTERVAL_MS));
        }
        lastTickWallMs = tickWall;
        if (!running.get()) {
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        long lossCapMs = gpsLossCapMs();
        boolean gpsLost = lastFixElapsedMs == 0 || (nowElapsed - lastFixElapsedMs) > lossCapMs;

        if (kalman.isInitialized() && !gpsLost) {
            // Project the fix-time state to now (covers both the time since the last fix and, with
            // latency compensation, the fix's own age) without disturbing the filter.
            double maxHorizonS = Math.max(MAX_HORIZON_S, lossCapMs / 1000.0 + 0.5);
            double horizonS = Math.max(0, Math.min((nowElapsed - stateElapsedMs) / 1000.0, maxHorizonS));
            lastHorizonMs = horizonS * 1000.0;
            injectSmoothed(kalman.extrapolate(horizonS));
        }
        // When GPS is lost, we simply stop advancing/injecting (freeze) until fixes resume.

        // Schedule on a fixed grid: postDelayed after the work drifted the rate down to ~8.7 Hz.
        long nowUptime = SystemClock.uptimeMillis();
        nextTickUptimeMs += OUTPUT_INTERVAL_MS;
        if (nextTickUptimeMs <= nowUptime || nextTickUptimeMs > nowUptime + OUTPUT_INTERVAL_MS) {
            nextTickUptimeMs = nowUptime + OUTPUT_INTERVAL_MS; // fell behind (or first tick): resync
        }
        mainHandler.postAtTime(smoothingTick, nextTickUptimeMs);
    }

    /** How long without a new fix counts as GPS lost: 2.5 fix intervals, at least 2.5 s. */
    private long gpsLossCapMs() {
        return Math.max(GPS_LOSS_CAP_MS, Math.min(MAX_GPS_LOSS_CAP_MS, Math.round(GPS_LOSS_INTERVALS * fixIntervalEmaMs)));
    }

    private void injectSmoothed(LocationKalmanFilter.Estimate est) {
        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setLatitude(est.latitude);
        loc.setLongitude(est.longitude);
        loc.setAltitude(lastAltitude);
        applyVerticalAccuracyFiltered(loc);
        double speed = est.speed;
        // Hysteresis around the standstill threshold: without it speed/bearing flickered on and off
        // 126 times in an hour of driving (creeping in traffic, pulling away).
        if (outputMoving) {
            outputMoving = speed >= filterConfig.standstillSpeed * MOVING_HIDE_FACTOR;
        } else {
            outputMoving = speed >= filterConfig.standstillSpeed * MOVING_SHOW_FACTOR;
        }
        boolean moving = outputMoving;
        filtSpeedAcc = est.speedAccuracy;
        filtBearingAcc = moving ? est.bearingAccuracyDeg : Double.NaN;
        if (filterConfig.reportUncertainty) {
            outAccuracy = est.accuracy68;
            outSpeedAcc = est.speedAccuracy;
            outBearingAcc = moving ? est.bearingAccuracyDeg : Double.NaN;
        } else {
            outAccuracy = est.accuracy;
            outSpeedAcc = Double.NaN;
            outBearingAcc = Double.NaN;
        }
        loc.setAccuracy((float) outAccuracy);
        if (moving) {
            loc.setSpeed((float) speed);
            loc.setBearing((float) est.bearingDeg);
            if (filterConfig.reportUncertainty) {
                loc.setSpeedAccuracyMetersPerSecond((float) outSpeedAcc);
                loc.setBearingAccuracyDegrees((float) outBearingAcc);
            }
        }
        loc.setTime(System.currentTimeMillis());
        loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        lastReceivedLocation = loc;
        lastUpdateTime = System.currentTimeMillis();
        try {
            mockLocationManager.setMockLocation(loc);
            if (trackRecorder != null) {
                // after setMockLocation: records what was actually injected (incl. static jitter)
                trackRecorder.out(SystemClock.elapsedRealtime(), loc.getLatitude(), loc.getLongitude(), moving, loc.getSpeed(), loc.getBearing(),
                        outAccuracy, outSpeedAcc, outBearingAcc, lastHorizonMs);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }
    }

    private void startMetricsSampler() {
        if (statsReader == null) statsReader = new AndroidSystemStatsReader(this);
        if (csvWriter == null) {
            csvWriter = new MetricsCsvWriter(new java.io.File(getCacheDir(), "logs"), "client", 5_000_000);
        }
        mainHandler.removeCallbacks(metricsTick);
        metricsPrimed = false;
        mainHandler.postDelayed(metricsTick, METRICS_INTERVAL_MS);
    }

    private void sampleMetrics() {
        try {
            // Compute + broadcast the snapshot every tick so the Monitor screen always shows live
            // link-health data; the metrics toggle only gates persistence (CSV + logcat).
            {
                MetricsSnapshot s = metrics.snapshot(statsReader.read());
                if (!metricsPrimed) {
                    metricsPrimed = true;
                } else {
                    String ts = metricsTs.format(new java.util.Date());
                    long uptimeS = SystemClock.elapsedRealtime() / 1000;
                    if (Preferences.metricsEnabled(this)) {
                        csvWriter.append(MetricsSnapshot.csvHeader(), s.toCsvRow(ts, uptimeS));
                        Log.i(METRICS_TAG, s.toLogLine());
                    }
                    sendBroadcast(new Intent("goodvin.locsync.METRICS")
                            .setPackage(getPackageName())
                            .putExtra("text", s.toDisplayString())
                            .putExtra("pktSentPerSec", s.pktSentPerSec)
                            .putExtra("pktRecvPerSec", s.pktRecvPerSec)
                            .putExtra("bytesSentPerSec", s.bytesSentPerSec)
                            .putExtra("bytesRecvPerSec", s.bytesRecvPerSec)
                            .putExtra("maxGapMs", s.maxGapMs)
                            .putExtra("ageMeanMs", s.ageMeanMs)
                            .putExtra("ageP95Ms", s.ageP95Ms)
                            .putExtra("cpuPct", s.cpuPct)
                            .putExtra("fixesPerSec", s.fixesPerSec));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "metrics sampling failed", e);
        }
        if (trackRecorder != null) {
            trackRecorder.flush();
        }
        broadcastFilterStats();
        mainHandler.postDelayed(metricsTick, METRICS_INTERVAL_MS);
    }

    /**
     * Keeps the Wi-Fi radio out of power save while we run. In power save the access point buffers
     * downlink packets until the next beacon, which delivered fixes hundreds of ms late and in pairs.
     * LOW_LATENCY (API 29+) only applies while our app is in the foreground with the screen on, so
     * HIGH_PERF is held as well for the usual case of the navigator being on screen.
     */
    @SuppressWarnings("deprecation")
    private void acquireWifiLocks() {
        android.net.wifi.WifiManager wm = getApplicationContext().getSystemService(android.net.wifi.WifiManager.class);
        if (wm == null) return;
        try {
            if (wifiLockHighPerf == null) {
                wifiLockHighPerf = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "locsync:hiperf");
                wifiLockHighPerf.setReferenceCounted(false);
            }
            wifiLockHighPerf.acquire();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (wifiLockLowLatency == null) {
                    wifiLockLowLatency = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "locsync:lowlat");
                    wifiLockLowLatency.setReferenceCounted(false);
                }
                wifiLockLowLatency.acquire();
            }
            AppLog.i(TAG, "Wi-Fi power save disabled (Wi-Fi locks held)");
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to acquire Wi-Fi locks", e);
        }
    }

    private void releaseWifiLocks() {
        try {
            if (wifiLockHighPerf != null && wifiLockHighPerf.isHeld()) wifiLockHighPerf.release();
            if (wifiLockLowLatency != null && wifiLockLowLatency.isHeld()) wifiLockLowLatency.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to release Wi-Fi locks", e);
        }
    }

    /** Opens/closes the track file to follow the "Record track" setting (checked on every fix). */
    private void updateTrackRecorder() {
        boolean wanted = Preferences.trackRecording(this);
        if (wanted && trackRecorder == null) {
            trackRecorder = new TrackRecorder(new java.io.File(getCacheDir(), "logs"));
            AppLog.i(TAG, "Track recording started");
        } else if (!wanted && trackRecorder != null) {
            trackRecorder.close();
            trackRecorder = null;
            AppLog.i(TAG, "Track recording stopped");
        }
    }

    /**
     * Sends the filter's live state to the Monitor screen as ready-to-show label/value rows, so new
     * diagnostics only need to be added here.
     */
    private void broadcastFilterStats() {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        addStat(labels, values, R.string.filter_out_accuracy, outAccuracy, "±%.1f m");
        labels.add(getString(R.string.filter_accuracy_mode));
        values.add(getString(filterConfig.reportUncertainty
                ? R.string.filter_accuracy_mode_68 : R.string.filter_accuracy_mode_legacy));
        addStat(labels, values, R.string.filter_out_speed_acc, filtSpeedAcc, "±%.2f m/s");
        addStat(labels, values, R.string.filter_out_bearing_acc, filtBearingAcc, "±%.1f°");
        addStat(labels, values, R.string.filter_in_speed_acc, inSpeedAcc, "±%.2f m/s");
        addStat(labels, values, R.string.filter_in_bearing_acc, inBearingAcc, "±%.1f°");
        labels.add(getString(R.string.filter_bearing_quantized));
        values.add(!kalman.isInitialized() ? "—" : getString(kalman.isBearingQuantized()
                ? (filterConfig.bearingCompensation ? R.string.filter_bearing_quantized_fixed : R.string.filter_bearing_quantized_yes)
                : R.string.filter_bearing_quantized_no));
        addStat(labels, values, R.string.filter_latency, lastLatencyMs, "%.0f ms");
        addStat(labels, values, R.string.filter_network_delay, lastNetworkDelayMs, "%.0f ms");
        addStat(labels, values, R.string.filter_horizon, lastHorizonMs, "%.0f ms");
        addStat(labels, values, R.string.filter_turn_rate,
                kalman.isInitialized() && filterConfig.turnModel ? kalman.getTurnRateDegPerSec() : Double.NaN,
                "%+.1f°/s");
        labels.add(getString(R.string.filter_motion));
        values.add(!kalman.isInitialized() ? "—" : getString(kalman.isStationary()
                ? R.string.filter_motion_stopped : R.string.filter_motion_moving));
        addStat(labels, values, R.string.filter_nis, kalman.getLastNis(), "%.1f");
        addStat(labels, values, R.string.filter_position_trust,
                kalman.isInitialized() ? Math.sqrt(kalman.getPositionNoiseScale()) : Double.NaN, "σ ×%.2f");
        addStat(labels, values, R.string.filter_nis_avg,
                kalman.isInitialized() ? kalman.getNisAverage() : Double.NaN, "%.1f");
        addStat(labels, values, R.string.filter_process_noise,
                kalman.isInitialized() ? kalman.getProcessNoise() : Double.NaN, "%.2f m/s²");
        addStat(labels, values, R.string.filter_outliers, kalman.getOutlierCount(), "%.0f");
        addStat(labels, values, R.string.filter_reinits, kalman.getReinitCount(), "%.0f");
        sendBroadcast(new Intent("goodvin.locsync.FILTER_STATS")
                .setPackage(getPackageName())
                .putExtra("labels", labels.toArray(new String[0]))
                .putExtra("values", values.toArray(new String[0])));
    }

    private void addStat(java.util.List<String> labels, java.util.List<String> values,
                         int labelRes, double v, String format) {
        labels.add(getString(labelRes));
        values.add(Double.isNaN(v) ? "—" : String.format(java.util.Locale.US, format, v));
    }

    private void broadcastMockLocationStatus(String message, boolean error) {
        Intent intent = new Intent("goodvin.locsync.MOCK_LOCATION_STATUS");
        intent.setPackage(getPackageName());
        intent.putExtra("message", message);
        intent.putExtra("error", error);
        sendBroadcast(intent);
    }

    /**
     * Broadcasts the satellite count to the external widget app, but only when the count changes.
     * Uses an explicit package target — required for receivers registered with RECEIVER_NOT_EXPORTED.
     */
    private void broadcastSatelliteStatusToWidget(int count) {
        if (count == lastBroadcastSatelliteCount) {
            return;
        }
        lastBroadcastSatelliteCount = count;
        Intent intent = new Intent(WIDGET_SATELLITE_STATUS_ACTION);
        intent.setPackage(WIDGET_PACKAGE);
        intent.putExtra("count", count);
        sendBroadcast(intent);
    }

    private static final long PHONE_STATE_STALE_MS = 30_000;

    /** The phone's battery/heat, or null when unknown or older than 30 s (server gone or too old). */
    public static LocationProto.PhoneState getPhoneState() {
        PhoneSample s = phoneSample;
        if (s == null || SystemClock.elapsedRealtime() - s.elapsedMs() > PHONE_STATE_STALE_MS) return null;
        return s.state();
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

    /** For per-fix refreshes (the age text): at most every few seconds, not on every packet. */
    private void updateNotificationThrottled() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationElapsedMs >= NOTIFICATION_MIN_INTERVAL_MS) {
            updateNotification();
        }
    }

    private void updateNotification() {
        lastNotificationElapsedMs = SystemClock.elapsedRealtime();
        boolean isConnected = connectionManager != null && connectionManager.isConnected();

        notificationManager.notify(NOTIFICATION_ID, createNotification(isConnected));
    }
}

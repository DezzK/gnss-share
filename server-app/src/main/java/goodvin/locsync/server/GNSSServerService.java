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

package goodvin.locsync.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import goodvin.locsync.shared.AppLog;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import goodvin.locsync.proto.LocationProto;
import goodvin.locsync.shared.AndroidSystemStatsReader;
import goodvin.locsync.shared.Metrics;
import goodvin.locsync.shared.MetricsCsvWriter;
import goodvin.locsync.shared.MetricsSnapshot;
import goodvin.locsync.shared.Protocol;
import goodvin.locsync.shared.SystemStatsReader;

public class GNSSServerService extends Service {
    private static final String TAG = "GNSSServerService";
    private static final String CHANNEL_ID = "GNSSServerChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_IS_SERVICE_ENABLED = "isServiceEnabled";
    private static final long CLIENT_TIMEOUT_MS = 5000;   // no HELLO for this long => client gone
    // Keepalive: the client needs a RESPONSE at least every ~3 s to stay "connected". Fixes already
    // arrive every second, so the latest response is resent only after KEEPALIVE_IDLE_MS without
    // one (no fix yet, GPS lost) instead of unconditionally every second, which doubled the traffic
    // and made the client parse and discard a duplicate per fix.
    private static final long KEEPALIVE_TICK_MS = 250;
    private static final long KEEPALIVE_IDLE_MS = 1500;
    private volatile long lastResponseSentElapsed = 0;

    private static boolean running = false;
    private static GNSSServerService instance = null;

    private String serverStartError = null;

    private volatile DatagramSocket udpSocket;
    private volatile SocketAddress clientAddr = null;   // the single current client
    private volatile long lastHeard = 0;                // elapsedRealtime of the last HELLO from clientAddr
    private final Runnable keepaliveRunnable = this::keepaliveTick;
    private LocationManager locationManager = null;
    private FusedLocationProviderClient fusedLocationProviderClient = null;
    private final com.google.android.gms.location.LocationListener fusedLocationListener = this::handleLocationUpdate;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            handleLocationUpdate(location);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            AppLog.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            AppLog.d(TAG, "Provider disabled: " + provider);
        }
    };

    private NotificationManager notificationManager;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Metrics metrics = new Metrics();
    private SystemStatsReader statsReader;
    private MetricsCsvWriter csvWriter;
    private volatile boolean metricsPrimed = false;
    private static final long METRICS_INTERVAL_MS = 1000;
    private static final String METRICS_TAG = "METRICS";
    private final Runnable metricsTick = this::sampleMetrics;
    private final java.text.SimpleDateFormat metricsTs =
            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            gnssStatus = status;
            int previous = satellitesUsed;
            satellitesUsed = countUsedInFix(status);   // once per status (~1 Hz), not per lookup
            lastServerResponse.setSatellites(satellitesUsed);

            // While waiting for the first fix the notification shows the satellite count: refresh it
            // when that count changes, not on every (~1 Hz) status callback.
            if (satellitesUsed != previous && isServiceRunning() && clientAddr != null
                    && !lastServerResponse.hasLocationUpdate()) {
                updateNotification("GNSS status changed");
            }
        }
    };

    // Main thread only (fix and GNSS status callbacks run on mainHandler); hand built messages to
    // other threads, never the builder.
    private final LocationProto.ServerResponse.Builder lastServerResponse = LocationProto.ServerResponse.newBuilder()
            .setStatus(LocationProto.Status.UNINITIALIZED);

    // We need to use such runnable to make scheduled stopping cancelable
    private final Runnable stopLocationUpdates = this::stopLocationUpdates;

    // Bluetooth auto-stop runnable
    private final Runnable btAutoStopRunnable = this::btAutoStopService;
    private WifiTrigger.Monitor wifiMonitor;   // follows the car's Wi-Fi while "Wi-Fi auto-start" is on

    private GnssStatus gnssStatus = null;
    private volatile int satellitesUsed = 0;
    private boolean isGnssActive = false;
    private boolean hadAccurateFix = true;                 // see Preferences.waitForAccurate
    private long lastFixElapsedNanos = 0;                  // monotonic time of the fix in lastServerResponse
    private ServerTrackRecorder trackRecorder;             // non-null while "Record track" is on
    private boolean gpsReferenceActive = false;
    // Raw GPS alongside Fused, for the track only: shows how much Fused smooths and lags.
    // An explicit class, not a lambda: before API 30 the other LocationListener callbacks are abstract.
    private final LocationListener gpsReferenceListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (trackRecorder != null) {
                trackRecorder.fix(SystemClock.elapsedRealtime(), "gps_ref", location, getSatelliteCount(),
                        false, clientAddr != null);
            }
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
        }
    };
    private static final float ACCURATE_FIX_M = 20f;
    private WifiManager.MulticastLock multicastLock;

    @Override
    public void onCreate() {
        AppLog.setDebug(Preferences.debugLoggingEnabled(this));
        notificationManager = getSystemService(NotificationManager.class);

        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        running = true;
        instance = this;
        ServerTileService.requestRefresh(this);
        syncWifiMonitor();

        WifiManager wifi = getSystemService(WifiManager.class);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("gnss-server");
            multicastLock.setReferenceCounted(false);
            try {
                multicastLock.acquire();
                AppLog.d(TAG, "MulticastLock acquired");
            } catch (Exception e) {
                Log.w(TAG, "Failed to acquire MulticastLock", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        serverStartError = null;
        startServer();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        ServerTileService.requestRefresh(this);
        if (wifiMonitor != null) {
            wifiMonitor.stop();
            wifiMonitor = null;
        }

        cancelBluetoothAutoStop();
        stopServer();
        stopLocationUpdates();

        locationManager = null;

        executor.shutdown();

        notificationManager.cancel(NOTIFICATION_ID);
        notificationManager = null;

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initializeLocationManager() {
        if (locationManager != null) {
            return;
        }

        locationManager = getSystemService(LocationManager.class);

        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);

            AppLog.d(TAG, "GNSS status callback registered");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to register GNSS status callback", e);
        }
    }

    private void initializeFusedLocationProviderClient() {
        // Supported on Android 12+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }

        // User opted out
        if (!Preferences.fusedLocationEnabled(this)) {
            return;
        }

        try {
            // Google Play Services are required
            if (!isGooglePlayServicesAvailable(this)) {
                return;
            }

            if (fusedLocationProviderClient != null) {
                return;
            }

            fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        } catch (NoClassDefFoundError e) {
            Log.w(TAG, "Google Play Services not available on this device", e);
            fusedLocationProviderClient = null;
        }
    }

    public static boolean isFusedLocationSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        try {
            GoogleApiAvailability api = GoogleApiAvailability.getInstance();
            return api.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    private void startServer() {
        executor.execute(() -> {
            // Guard against START_STICKY redelivery (or any re-entry) while already bound: binding
            // the port again would throw BindException and the catch below would tear down the
            // live socket. If we're already running, do nothing.
            if (udpSocket != null && !udpSocket.isClosed()) {
                AppLog.d(TAG, "UDP server already running; ignoring start request");
                return;
            }
            try {
                udpSocket = new DatagramSocket(Protocol.PORT);
                AppLog.d(TAG, "UDP server bound on port " + Protocol.PORT);
            } catch (Throwable e) {
                Log.e(TAG, "Error starting UDP server", e);
                serverStartError = e.getMessage();
                stopServer();
                return;
            }

            mainHandler.post(() -> mainHandler.postDelayed(keepaliveRunnable, KEEPALIVE_TICK_MS));
            mainHandler.post(this::startMetricsSampler);

            byte[] buffer = new byte[Protocol.MAX_PACKET_BYTES];
            while (udpSocket != null && !udpSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);
                    handlePacket(packet);
                } catch (IOException e) {
                    if (udpSocket != null && !udpSocket.isClosed()) {
                        Log.e(TAG, "Error receiving UDP packet", e);
                    }
                }
            }
        });
    }

    private void handlePacket(DatagramPacket packet) {
        Protocol.Header header;
        try {
            header = Protocol.parse(packet.getData(), packet.getLength());
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Discarding malformed packet from " + packet.getSocketAddress());
            return;
        }

        if (!Protocol.isSupportedVersion(header.version)) {
            Log.w(TAG, "Version mismatch from " + packet.getSocketAddress() + ": " + header.version);
            sendPacket(Protocol.buildPacket(Protocol.TYPE_VERSION_MISMATCH,
                    new byte[]{(byte) Protocol.VERSION}), packet.getSocketAddress());
            return;
        }

        if (header.type == Protocol.TYPE_HELLO) {
            boolean isNewClient = (clientAddr == null);
            clientAddr = packet.getSocketAddress();
            lastHeard = SystemClock.elapsedRealtime();   // monotonic: wall-clock changes can't fake a timeout
            if (isNewClient) {
                AppLog.i(TAG, "Client present: " + clientAddr);
                mainHandler.post(this::startLocationUpdates);
                cancelBluetoothAutoStop();
                mainHandler.post(() -> updateNotification("Client connected"));
                ServerTileService.requestRefresh(this);
            }
        } else {
            Log.w(TAG, "Unexpected packet type from client: " + header.type);
        }
    }

    private void sendPacket(byte[] data, SocketAddress dest) {
        if (udpSocket == null || udpSocket.isClosed() || dest == null) {
            return;
        }
        try {
            udpSocket.send(new DatagramPacket(data, data.length, dest));
            metrics.recordPacketSent(data.length);
        } catch (IOException e) {
            Log.w(TAG, "Error sending UDP packet", e);
        }
    }

    private void stopServer() {
        AppLog.d(TAG, "Stopping UDP server");
        mainHandler.removeCallbacks(keepaliveRunnable);
        mainHandler.removeCallbacks(metricsTick);
        metricsPrimed = false;
        lastServerResponse.clearPhone();   // re-sampled when the server starts again
        clientAddr = null;
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
    }

    private void startMetricsSampler() {
        if (statsReader == null) statsReader = new AndroidSystemStatsReader(this);
        if (csvWriter == null) {
            csvWriter = new MetricsCsvWriter(new java.io.File(getCacheDir(), "logs"), "server", 5_000_000);
        }
        mainHandler.removeCallbacks(metricsTick);
        metricsPrimed = false;
        phoneStateTick = 1;   // the next sample is due in 5 ticks: take this one now
        samplePhoneStateSafely();
        mainHandler.postDelayed(metricsTick, METRICS_INTERVAL_MS);
    }

    private static final int PHONE_STATE_EVERY_TICKS = 5;   // battery/heat change slowly
    private int phoneStateTick = 0;

    /** Battery level, charging and heat for the head unit (sent with every response). */
    private void samplePhoneState() {
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        LocationProto.PhoneState.Builder phone = LocationProto.PhoneState.newBuilder();
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) phone.setBatteryPercent(Math.round(level * 100f / scale));
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        phone.setCharging(status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (tenths > 0) phone.setBatteryTempC(tenths / 10f);   // some phones report 0 for "unknown"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) phone.setThermalStatus(pm.getCurrentThermalStatus());
        }
        lastServerResponse.setPhone(phone);   // main thread, like every other builder write
    }

    private void samplePhoneStateSafely() {
        try {
            samplePhoneState();
        } catch (RuntimeException e) {
            Log.w(TAG, "phone state sampling failed", e);
        }
    }

    private void sampleMetrics() {
        if (phoneStateTick++ % PHONE_STATE_EVERY_TICKS == 0) samplePhoneStateSafely();
        try {
            // Compute + broadcast the snapshot every tick so the Monitor screen always shows live
            // link-health data; the metrics toggle only gates persistence (CSV + logcat).
            {
                MetricsSnapshot s = metrics.snapshot(statsReader.read());
                if (!metricsPrimed) {
                    metricsPrimed = true; // first call primed the baseline; skip emitting garbage window
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
        mainHandler.postDelayed(metricsTick, METRICS_INTERVAL_MS);
    }

    private void startLocationUpdates() {
        // If location updates were scheduled to be stopped, remove the scheduled action
        mainHandler.removeCallbacks(this.stopLocationUpdates);
        if (isGnssActive) {
            // Client came back while GPS was still running (within the idle-stop window): keep the
            // running request instead of restarting GNSS (and re-waiting for an accurate fix).
            AppLog.d(TAG, "Location updates already running");
            return;
        }

        initializeLocationManager();
        initializeFusedLocationProviderClient();

        try {
            AppLog.d(TAG, "Starting location updates...");

            lastServerResponse.setStatus(LocationProto.Status.AWAITING_LOCATION);

            // Default 1 s: most phones' GNSS delivers ~1 fix/s whatever is asked. Configurable because
            // some deliver 2-5 Hz, and every extra fix lets the client see a turn sooner.
            final int MIN_INTERVAL_MS = Math.max(100, Preferences.locationIntervalMs(this));
            final int MIN_DISTANCE_M = 0;
            boolean waitAccurate = Preferences.waitForAccurate(this);
            hadAccurateFix = !waitAccurate;
            if (fusedLocationProviderClient != null) {
                LocationRequest request = new LocationRequest.Builder(MIN_INTERVAL_MS)
                        .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
                        .setMaxUpdateDelayMillis(0)                 // never batch: every fix at once
                        .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                        .setWaitForAccurateLocation(waitAccurate)
                        .setPriority(Preferences.balancedPower(this)
                                ? Priority.PRIORITY_BALANCED_POWER_ACCURACY : Priority.PRIORITY_HIGH_ACCURACY)
                        .setGranularity(Granularity.GRANULARITY_FINE)
                        .build();
                final FusedLocationProviderClient fused = fusedLocationProviderClient;
                fused.requestLocationUpdates(request, fusedLocationListener, Looper.getMainLooper())
                        .addOnFailureListener(e -> {
                            // The request was silently ignored before: no fixes at all, forever.
                            // Fall back to the platform GPS provider instead.
                            Log.w(TAG, "Fused location request failed, falling back to GPS", e);
                            AppLog.w(TAG, "Fused location request failed (" + e.getMessage() + "), using GPS");
                            if (fusedLocationProviderClient == fused && isGnssActive) {
                                fused.removeLocationUpdates(fusedLocationListener);
                                fusedLocationProviderClient = null;
                                requestGpsUpdates(MIN_INTERVAL_MS);
                            }
                        });
            } else {
                requestGpsUpdates(MIN_INTERVAL_MS);
            }

            if (fusedLocationProviderClient != null && Preferences.trackRecording(this)
                    && Preferences.recordGpsReference(this) && locationManager != null) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_INTERVAL_MS, 0,
                        gpsReferenceListener, Looper.getMainLooper());
                gpsReferenceActive = true;
                AppLog.i(TAG, "Recording raw GPS alongside Fused for comparison");
            }
            AppLog.i(TAG, "Location updates started: " + (fusedLocationProviderClient != null ? "fused" : "gps")
                    + ", interval " + MIN_INTERVAL_MS + " ms" + (waitAccurate ? ", waiting for an accurate fix" : ""));

            isGnssActive = true;

            updateNotification("Started location updates");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting location updates", e);
        }
    }

    private void requestGpsUpdates(int intervalMs) {
        try {
            // Explicit main looper: the listener touches main-thread-only state.
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMs, 0,
                    locationListener, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
    }

    /**
     * Re-issues the location request with the current settings (interval, priority, provider) if
     * updates are running, so changes in Settings apply without restarting the server.
     */
    public static void reapplyLocationSettings() {
        GNSSServerService s = instance;
        if (s != null) {
            s.mainHandler.post(s::restartLocationUpdates);
        }
    }

    private void restartLocationUpdates() {
        if (!isGnssActive) {
            return;
        }
        AppLog.i(TAG, "Re-applying location settings");
        isGnssActive = false;   // let startLocationUpdates re-issue the request
        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
            stopGpsReference();
        }
        if (fusedLocationProviderClient != null) {
            fusedLocationProviderClient.removeLocationUpdates(fusedLocationListener);
            fusedLocationProviderClient = null;   // re-evaluated: the Fused toggle may have changed
        }
        startLocationUpdates();
    }

    private void stopLocationUpdates() {
        if (running && clientAddr != null) {
            Log.w(TAG, "Location updates not stopped: still have clients connected");
            return;
        }

        AppLog.d(TAG, "Stopping location updates...");

        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
            stopGpsReference();
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            locationManager = null;
        }

        if (fusedLocationProviderClient != null) {
            fusedLocationProviderClient.removeLocationUpdates(fusedLocationListener);
            fusedLocationProviderClient = null;
        }

        AppLog.d(TAG, "Location updates stopped");

        isGnssActive = false;
        // Forget the last fix: it would otherwise be resent (with its age frozen at "fresh") to the
        // next client before the first new fix, putting the icon where the car was last time.
        lastServerResponse.setStatus(LocationProto.Status.LOCATION_STOPPED).clearLocationUpdate();
        lastFixElapsedNanos = 0;
        // The status callback is unregistered above: drop the last count instead of showing it frozen.
        gnssStatus = null;
        satellitesUsed = 0;
        lastServerResponse.setSatellites(0);
        if (trackRecorder != null) {
            trackRecorder.close();
            trackRecorder = null;
        }

        updateNotification("Stopped location updates");
    }

    private void handleLocationUpdate(Location location) {
        long receivedElapsed = SystemClock.elapsedRealtime();
        long fixNanos = location.getElapsedRealtimeNanos();
        if (fixNanos > 0 && fixNanos <= lastFixElapsedNanos) {
            // A repeat or an older fix (Fused re-delivers its last location when a request is
            // re-issued): the client already has something newer.
            AppLog.d(TAG, "Ignoring repeated/older fix");
            return;
        }
        updateTrackRecorder();
        if (!hadAccurateFix) {
            // "Wait for an accurate fix": hold back the coarse first fixes so the client's icon
            // doesn't start tens of metres off and then jump.
            if (!location.hasAccuracy() || location.getAccuracy() > ACCURATE_FIX_M) {
                AppLog.d(TAG, "Skipping coarse fix while waiting for an accurate one: " + location.getAccuracy() + " m");
                recordFix(receivedElapsed, location, false);
                return;
            }
            hadAccurateFix = true;
        }
        recordFix(receivedElapsed, location, clientAddr != null);
        metrics.recordFix();

        AppLog.d(TAG, "Handling location update: " + location);

        // Create protobuf message
        LocationProto.LocationUpdate.Builder builder = LocationProto.LocationUpdate.newBuilder()
                .setTimestamp(location.getTime())
                .setLatitude(location.getLatitude())
                .setLongitude(location.getLongitude())
                .setProvider(location.getProvider())
                .setLocationAge(fixAgeSeconds(location));

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
        if (location.hasSpeedAccuracy()) {
            builder.setSpeedAccuracy(location.getSpeedAccuracyMetersPerSecond());
        }
        if (location.hasBearingAccuracy()) {
            builder.setBearingAccuracy(location.getBearingAccuracyDegrees());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
            builder.setVerticalAccuracy(location.getVerticalAccuracyMeters());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude()) {
            builder.setMslAltitude(location.getMslAltitudeMeters());
            if (location.hasMslAltitudeAccuracy()) {
                builder.setMslAltitudeAccuracy(location.getMslAltitudeAccuracyMeters());
            }
        }

        lastServerResponse.setStatus(LocationProto.Status.TRANSMITTING_LOCATION)
                .setLocationUpdate(builder.build());
        lastFixElapsedNanos = fixNanos;

        // Per-fix refresh at most every few seconds; state changes still update immediately.
        long now = SystemClock.elapsedRealtime();
        if (now - lastFixNotificationElapsedMs >= FIX_NOTIFICATION_MIN_INTERVAL_MS) {
            lastFixNotificationElapsedMs = now;
            updateNotification("Received location update");
        }

        // Broadcast to the connected client
        // Build on this (main) thread: the builder is mutated here (fixes, GNSS status callbacks), so
        // building it on the executor thread raced with those writes.
        LocationProto.ServerResponse resp = lastServerResponse.build();
        executor.execute(() -> broadcastLocationUpdate(resp));
    }

    private void recordFix(long receivedElapsed, Location location, boolean sent) {
        if (trackRecorder != null) {
            String source = "fused".equals(location.getProvider()) ? "fused" : "gps";
            trackRecorder.fix(receivedElapsed, source, location, getSatelliteCount(), sent, clientAddr != null);
        }
    }

    private void stopGpsReference() {
        if (gpsReferenceActive && locationManager != null) {
            locationManager.removeUpdates(gpsReferenceListener);
        }
        gpsReferenceActive = false;
    }

    /** Opens/closes track-server.csv to follow the "Record track" setting (checked on every fix). */
    private void updateTrackRecorder() {
        boolean wanted = Preferences.trackRecording(this);
        if (wanted && trackRecorder == null) {
            trackRecorder = new ServerTrackRecorder(new java.io.File(getCacheDir(), "logs"));
            AppLog.i(TAG, "Track recording started");
        } else if (!wanted && trackRecorder != null) {
            trackRecorder.close();
            trackRecorder = null;
            AppLog.i(TAG, "Track recording stopped");
        }
    }

    /**
     * Age of the fix now. From the monotonic fix timestamp: Location.getTime() is GNSS (UTC) time
     * for the GPS provider, and a phone clock seconds off would have shifted the client's latency
     * compensation by those seconds.
     */
    static float fixAgeSeconds(Location location) {
        long fixNanos = location.getElapsedRealtimeNanos();
        if (fixNanos > 0) {
            return Math.max(0, (SystemClock.elapsedRealtimeNanos() - fixNanos) / 1e9f);
        }
        return Math.max(0, (System.currentTimeMillis() - location.getTime()) / 1000.0f);
    }

    private void broadcastLocationUpdate(LocationProto.ServerResponse serverResponse) {
        SocketAddress dest = clientAddr;
        if (dest == null) {
            return;
        }
        lastResponseSentElapsed = SystemClock.elapsedRealtime();
        sendPacket(Protocol.buildPacket(Protocol.TYPE_RESPONSE, serverResponse.toByteArray()), dest);
    }

    private void keepaliveTick() {
        SocketAddress dest = clientAddr;
        if (dest != null) {
            long silence = SystemClock.elapsedRealtime() - lastHeard;
            if (silence > CLIENT_TIMEOUT_MS) {
                AppLog.i(TAG, "Client timed out (" + silence + "ms), marking gone");
                if (clientAddr == dest) {          // still the same client we timed out
                    clientAddr = null;
                    onClientGone();
                }
            } else if (SystemClock.elapsedRealtime() - lastResponseSentElapsed >= KEEPALIVE_IDLE_MS) {
                // Nothing sent for a while: resend the latest response so the client stays connected,
                // with the fix's age as of now (not as of when it was first sent).
                if (lastServerResponse.hasLocationUpdate() && lastFixElapsedNanos > 0) {
                    float age = Math.max(0, (SystemClock.elapsedRealtimeNanos() - lastFixElapsedNanos) / 1e9f);
                    lastServerResponse.setLocationUpdate(
                            lastServerResponse.getLocationUpdate().toBuilder().setLocationAge(age));
                }
                LocationProto.ServerResponse resp = lastServerResponse.build();
                executor.execute(() -> broadcastLocationUpdate(resp));
            }
        }
        if (running) {
            mainHandler.postDelayed(keepaliveRunnable, KEEPALIVE_TICK_MS);
        }
    }

    private void onClientGone() {
        ServerTileService.requestRefresh(this);
        long idleMs = Preferences.gpsIdleStopSeconds(this) * 1000L;
        AppLog.d(TAG, "No client; scheduling stop of location updates in " + idleMs + " ms");
        mainHandler.removeCallbacks(this.stopLocationUpdates);
        mainHandler.postDelayed(this.stopLocationUpdates, idleMs);
        evaluateAutoStop();
        mainHandler.post(() -> updateNotification("Client disconnected"));
    }

    public static boolean isServiceRunning() {
        return running;
    }

    /** True when a HELLO-registered client is currently present (drives {@code LinkState.CONNECTED}). */
    public static boolean isClientConnected() {
        return instance != null && instance.clientAddr != null;
    }

    /** Satellites used in the fix, or 0 when the service is not running. */
    public static int currentSatelliteCount() {
        return instance != null ? instance.getSatelliteCount() : 0;
    }

    /** Total satellites currently tracked (all constellations), or 0 when not running. */
    public static int currentVisibleSatelliteCount() {
        return (instance != null && instance.gnssStatus != null)
                ? instance.gnssStatus.getSatelliteCount() : 0;
    }

    /** C/N0 (dB-Hz) of the used-in-fix satellites, strongest first, up to 14. Null when unavailable. */
    public static float[] currentUsedCn0() {
        if (instance == null || instance.gnssStatus == null) {
            return null;
        }
        GnssStatus s = instance.gnssStatus;
        int n = s.getSatelliteCount();
        java.util.ArrayList<Float> vals = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (s.usedInFix(i)) {
                vals.add(s.getCn0DbHz(i));
            }
        }
        vals.sort(java.util.Collections.reverseOrder());
        int m = Math.min(vals.size(), 14);
        float[] out = new float[m];
        for (int i = 0; i < m; i++) {
            out[i] = vals.get(i);
        }
        return out;
    }

    /** The most recent GNSS fix the server is transmitting, or null when none/stopped. */
    public static LocationProto.LocationUpdate currentLocationUpdate() {
        if (instance == null) {
            return null;
        }
        LocationProto.ServerResponse resp = instance.lastServerResponse.build();
        return resp.hasLocationUpdate() ? resp.getLocationUpdate() : null;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.app_description));
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String content;
        if (serverStartError == null) {
            if (clientAddr != null) {
                content = getString(R.string.notification_clients_single);
            } else {
                content = getString(R.string.notification_no_clients);
            }

            content += getString(R.string.notification_divider);

            if (isGnssActive) {
                content += String.format(
                        getString(R.string.notification_satellites),
                        getSatelliteCount()
                );


                if (lastServerResponse.hasLocationUpdate() && lastFixElapsedNanos > 0) {
                    // Monotonic, like the age sent to the client: the fix timestamp is GPS time and
                    // the phone's wall clock can be off by seconds.
                    content += getString(R.string.notification_divider) + String.format(
                            getString(R.string.notification_age),
                            (SystemClock.elapsedRealtimeNanos() - lastFixElapsedNanos) / 1e9
                    );
                }
            } else {
                content += getString(R.string.notification_gnss_inactive);
            }
        } else {
            content = serverStartError;
        }

        // Stop action for notification shade
        Intent stopIntent = new Intent("goodvin.locsync.server.STOP");
        stopIntent.setPackage(getPackageName());
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(String.format(getString(serverStartError == null ? R.string.notification_title : R.string.notification_failed_title), getString(R.string.app_name)))
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.disable_service), stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    private static final long FIX_NOTIFICATION_MIN_INTERVAL_MS = 5000;
    private long lastFixNotificationElapsedMs = 0;

    private void updateNotification(String reason) {
        if (notificationManager == null) {
            return;
        }
        AppLog.d(TAG, "Updating notification: " + reason);
        notificationManager.notify(NOTIFICATION_ID, createNotification());
    }

    public int getSatelliteCount() {
        return satellitesUsed;
    }

    // Report satellites actually used in the fix rather than every satellite tracked, so the count
    // is meaningful (e.g. ~8-14) instead of the raw all-constellation total (~80+).
    private static int countUsedInFix(GnssStatus status) {
        int used = 0;
        int total = status.getSatelliteCount();
        for (int i = 0; i < total; i++) {
            if (status.usedInFix(i)) {
                used++;
            }
        }
        return used;
    }

    private boolean isGooglePlayServicesAvailable(Context context) {
        GoogleApiAvailability googleApiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context);
        return resultCode == ConnectionResult.SUCCESS;
    }

    // Bluetooth auto-stop methods
    //
    // Unified logic:
    //   - evaluateAutoStop() is called on BT disconnect, on leaving the car's Wi-Fi and on last client
    //     disconnect. Schedules a stop (Settings: stop delay) only when every enabled trigger (BT
    //     devices, car Wi-Fi) AND all clients are gone.
    //   - cancelBluetoothAutoStop() is called on BT/Wi-Fi reconnect and on new client connect.
    //   - btAutoStopService() re-checks conditions as a safety net before actually stopping.

    /** Settings changed: follow the car's Wi-Fi (or stop following it) while the server runs. */
    public static void refreshWifiMonitor() {
        GNSSServerService s = instance;
        if (s != null) s.mainHandler.post(s::syncWifiMonitor);
    }

    private void syncWifiMonitor() {
        boolean wanted = WifiTrigger.isActive(this);
        if (wanted && wifiMonitor == null) {
            wifiMonitor = new WifiTrigger.Monitor(this, () -> mainHandler.post(this::doEvaluateAutoStop));
            wifiMonitor.start();
        } else if (!wanted && wifiMonitor != null) {
            wifiMonitor.stop();
            wifiMonitor = null;
        }
    }

    /**
     * Called from BluetoothReceiver (BT disconnect), on leaving the car's Wi-Fi and from onClientGone
     * (client timed out). Schedules auto-stop only if both conditions are met.
     */
    public static void evaluateAutoStop() {
        if (instance != null) {
            instance.doEvaluateAutoStop();
        }
    }

    /** Called from BluetoothReceiver (BT reconnect) and handlePacket (new client connect). */
    public static void cancelBluetoothAutoStopRequest() {
        if (instance != null) {
            instance.cancelBluetoothAutoStop();
        }
    }

    private void doEvaluateAutoStop() {
        if (!running) return;

        // Only auto-stop if Bluetooth or Wi-Fi auto-start/stop is enabled
        boolean bt = Preferences.bluetoothAutoStartEnabled(this);
        boolean wifi = WifiTrigger.isActive(this);
        if (!bt && !wifi) {
            AppLog.d(TAG, "Auto-start/stop disabled in preferences, skipping auto-stop evaluation");
            return;
        }

        boolean triggersGone = triggersGone();
        boolean clientsGone = (clientAddr == null);

        if (triggersGone && clientsGone) {
            long delayMs = Preferences.bluetoothStopDelaySeconds(this) * 1000L;
            AppLog.d(TAG, "Car (Bluetooth/Wi-Fi) and clients gone, scheduling auto-stop in " + delayMs + "ms");
            mainHandler.removeCallbacks(btAutoStopRunnable);
            mainHandler.postDelayed(btAutoStopRunnable, delayMs);
        } else {
            AppLog.d(TAG, "Auto-stop not needed (car connected: " + !triggersGone + ", clients connected: " + !clientsGone + ")");
        }
    }

    /** No enabled trigger (Bluetooth device, car Wi-Fi) is connected. */
    private boolean triggersGone() {
        boolean btGone = !Preferences.bluetoothAutoStartEnabled(this) || BluetoothReceiver.allTriggerDevicesDisconnected();
        boolean wifiGone = !WifiTrigger.isActive(this) || !WifiTrigger.isConnected();
        return btGone && wifiGone;
    }

    private void cancelBluetoothAutoStop() {
        AppLog.d(TAG, "Cancelling Bluetooth auto-stop");
        mainHandler.removeCallbacks(btAutoStopRunnable);
    }

    private void btAutoStopService() {
        // Safety net: re-check conditions before stopping
        boolean triggersGone = triggersGone();
        boolean clientsGone = (clientAddr == null);
        if (!triggersGone || !clientsGone) {
            AppLog.i(TAG, "Auto-stop skipped (car connected: " + !triggersGone + ", clients connected: " + !clientsGone + ")");
            return;
        }
        AppLog.i(TAG, "Auto-stop triggered (car Bluetooth/Wi-Fi gone) - stopping service");
        setServiceEnabled(this, false);
        stopSelf();
    }

}
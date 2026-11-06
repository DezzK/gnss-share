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

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.IntentCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import goodvin.locsync.proto.LocationProto;
import goodvin.locsync.shared.AppLog;
import goodvin.locsync.shared.LinkState;
import goodvin.locsync.shared.LogExporter;
import goodvin.locsync.shared.PowerOrbView;
import goodvin.locsync.shared.SettingsRows;
import goodvin.locsync.shared.SetupChecklist;
import goodvin.locsync.shared.SparklineView;
import goodvin.locsync.shared.UpdateChecker;
import goodvin.locsync.shared.UpdateRow;
import goodvin.locsync.shared.VersionGetter;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GNSSClientActivity";
    private static final int VIEW_CONNECT = 0, VIEW_MONITOR = 1, VIEW_SETTINGS = 2, VIEW_SETUP = 3;

    // Head units have large, low-density screens where dp-sized UI reads tiny. Scale the whole UI
    // (dp + sp uniformly) by raising the effective density: automatically ×2 on large screens (phones
    // untouched), or the multiplier chosen in Settings → Display.
    private static final float LARGE_SCREEN_UI_SCALE = 2.0f;
    private static final int LARGE_SCREEN_MIN_SW_DP = 600;
    private static final float[] UI_SCALES = {0f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f};   // 0 = auto
    private static final String STATE_VIEW = "view";
    private static final String STATE_SETTINGS_SCROLL = "settingsScroll";
    private static final String STATE_FILTER_ADVANCED = "filterAdvanced";

    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    // Top bar
    private ViewFlipper viewFlipper;
    private ImageView btnLeft, btnRight;
    private TextView titleText, subtitleText;
    private androidx.activity.OnBackPressedCallback backCallback;

    // Connect
    private PowerOrbView powerOrb;
    private TextView statusLine, statusSub, bannerText;
    private View connectBanner;
    private View tileSpeed, tileAccuracy, tileSats, tileFresh;
    private View navDot;
    private TextView navTitle, navDetail;

    // Monitor
    private TextView monLocation, monAltAcc, logText;
    private View logDot;
    private SparklineView sparkAgeView, sparkPktView, sparkSatView;
    private TextView sparkAgeVal, sparkPktVal, sparkSatVal;

    private boolean liveMonitoring = false;   // real-time Monitor updates, off by default

    private final Handler uiHandler = new Handler();
    private VehicleData vehicleData;
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private String appVersion = "<unknown>";
    private String warningMessage = null;   // version mismatch / mock-provider error, shown in banner
    private boolean mockError = false;
    private Runnable bannerAction = null;    // what tapping the connect banner does (depends on the issue)
    private UpdateRow updateRow;
    private boolean bannerIsUpdate;
    private static boolean updateBannerDismissed;   // for this app process, across recreates
    private long connectedSinceElapsed = 0;

    // Latest values for the connect/monitor readouts.
    private int lastSatellites = 0;
    private Location lastLocation = null;
    private String lastProvider = null;
    private float lastLocationAge = 0;
    private long lastLocationRecvElapsed = 0;   // when the last location broadcast arrived
    private double mPktRecv = Double.NaN, mPktSent = Double.NaN, mBytesRecv = Double.NaN,
            mBytesSent = Double.NaN, mMaxGap = Double.NaN, mAgeMean = Double.NaN,
            mAgeP95 = Double.NaN, mCpu = Double.NaN, mFixes = Double.NaN;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = !result.containsValue(false);
                if (allGranted) {
                    Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                    checkMockLocationSettings();
                } else {
                    Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                    refreshPermissions();
                }
            });

    private final ActivityResultLauncher<Intent> mockLocationSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result ->
                    refreshPermissions());

    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.CONNECTION_CHANGED".equals(intent.getAction())) {
                refreshState();
            }
        }
    };

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.LOCATION_UPDATE".equals(intent.getAction())) {
                lastSatellites = intent.getIntExtra("satellites", 0);
                Location location = IntentCompat.getParcelableExtra(intent, "location", Location.class);
                if (location != null) {
                    lastLocation = location;
                    lastProvider = intent.getStringExtra("provider");
                    lastLocationAge = intent.getFloatExtra("locationAge", 0);
                    lastLocationRecvElapsed = SystemClock.elapsedRealtime();
                }
                updateConnectReadouts();
                if (liveMonitoring) {
                    sparkSatView.push(lastSatellites);
                    updateMonitorLocation();
                }
            }
        }
    };

    private final BroadcastReceiver mockLocationStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.MOCK_LOCATION_STATUS".equals(intent.getAction())) {
                warningMessage = intent.getStringExtra("message");
                mockError = intent.getBooleanExtra("error", true);
                refreshState();
            }
        }
    };

    private final BroadcastReceiver metricsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.METRICS".equals(intent.getAction())) {
                mPktRecv = intent.getDoubleExtra("pktRecvPerSec", Double.NaN);
                mPktSent = intent.getDoubleExtra("pktSentPerSec", Double.NaN);
                mBytesRecv = intent.getDoubleExtra("bytesRecvPerSec", Double.NaN);
                mBytesSent = intent.getDoubleExtra("bytesSentPerSec", Double.NaN);
                mMaxGap = intent.getDoubleExtra("maxGapMs", Double.NaN);
                mAgeMean = intent.getDoubleExtra("ageMeanMs", Double.NaN);
                mAgeP95 = intent.getDoubleExtra("ageP95Ms", Double.NaN);
                mCpu = intent.getDoubleExtra("cpuPct", Double.NaN);
                mFixes = intent.getDoubleExtra("fixesPerSec", Double.NaN);
                updateConnectReadouts();
                if (liveMonitoring) {
                    if (!Double.isNaN(mAgeMean)) sparkAgeView.push((float) mAgeMean);
                    if (!Double.isNaN(mPktRecv)) sparkPktView.push((float) mPktRecv);
                    updateMonitorMetrics();
                }
            }
        }
    };

    private final BroadcastReceiver filterStatsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("goodvin.locsync.FILTER_STATS".equals(intent.getAction()) && liveMonitoring) {
                renderFilterStats(intent.getStringArrayExtra("labels"), intent.getStringArrayExtra("values"));
            }
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        android.content.res.Configuration config =
                new android.content.res.Configuration(base.getResources().getConfiguration());
        float scale = effectiveUiScale(Preferences.uiScale(base), config.smallestScreenWidthDp);
        if (scale != 1f) {
            config.densityDpi = Math.round(config.densityDpi * scale);
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    /** The multiplier actually applied: the chosen one, or ×2 on large screens / ×1 on phones for "auto". */
    static float effectiveUiScale(float chosen, int smallestWidthDp) {
        if (chosen > 0) return chosen;
        return smallestWidthDp >= LARGE_SCREEN_MIN_SW_DP ? LARGE_SCREEN_UI_SCALE : 1f;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (viewFlipper == null) return;   // onCreate failed early; don't mask its error
        // Settings rows that recreate the activity (interface size, presets) keep the page and position.
        outState.putInt(STATE_VIEW, viewFlipper.getDisplayedChild());
        outState.putInt(STATE_SETTINGS_SCROLL, findViewById(R.id.viewSettings).getScrollY());
        outState.putBoolean(STATE_FILTER_ADVANCED,
                findViewById(R.id.cardFilterAdvanced).getVisibility() == View.VISIBLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        setContentView(R.layout.activity_main);

        appVersion = VersionGetter.getAppVersionName(this);
        AppLog.setDebug(Preferences.debugLoggingEnabled(this));
        // Manual server-address UI was removed; the client always auto-discovers.
        Preferences.setAutoDiscover(this, true);
        liveMonitoring = Preferences.liveMonitoring(this);

        bindTopBar();
        bindConnect();
        bindMonitor();
        bindSettings();
        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(STATE_FILTER_ADVANCED)) setFilterAdvancedVisible(true);
            int scrollY = savedInstanceState.getInt(STATE_SETTINGS_SCROLL);
            View settings = findViewById(R.id.viewSettings);
            settings.post(() -> settings.scrollTo(0, scrollY));   // after the first layout
        }
        registerReceivers();

        int firstView = savedInstanceState != null ? savedInstanceState.getInt(STATE_VIEW, VIEW_CONNECT) : VIEW_CONNECT;
        if (savedInstanceState == null && !Preferences.setupShown(this)) {
            if (SetupChecklist.isFreshInstall(this)) {
                firstView = VIEW_SETUP;   // first start: walk through what the app needs (marked seen in onResume)
            } else {
                Preferences.setSetupShown(this);   // updated from an older version: already set up
            }
        }
        showView(firstView);
        refreshPermissions();
        refreshState();
        startUIUpdates();

        if (GNSSClientService.isServiceEnabled(this) && !GNSSClientService.isServiceRunning()) {
            startGNSSService();
        }
        if (GNSSClientService.isServiceEnabled(this) && Preferences.autostartWifi(this)) {
            AutostartScheduler.schedule(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(connectionReceiver);
        unregisterReceiver(locationReceiver);
        unregisterReceiver(mockLocationStatusReceiver);
        unregisterReceiver(metricsReceiver);
        unregisterReceiver(filterStatsReceiver);
        uiHandler.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Seen only once it is actually on screen (not when an autostart creates the activity unseen).
        if (viewFlipper.getDisplayedChild() == VIEW_SETUP) Preferences.setSetupShown(this);
        mockCheckedElapsed = 0;   // e.g. returning from Developer options
        // Recompute from the service rather than assuming a state; also re-check mock-app selection.
        refreshPermissions();
        refreshState();
        refreshA11yAutostartRow();
        refreshTrackRow();
        // Poll the head unit's own signals (speed, fuel, temps) only while on screen.
        if (vehicleData == null) {
            vehicleData = new VehicleData(this, uiHandler, this::updateCarCard);
        }
        vehicleData.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (vehicleData != null) {
            vehicleData.stop();
        }
    }

    // --- binding ---

    private void bindTopBar() {
        viewFlipper = findViewById(R.id.viewFlipper);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        titleText = findViewById(R.id.titleText);
        subtitleText = findViewById(R.id.subtitleText);
        btnLeft.setOnClickListener(v -> {
            if (viewFlipper.getDisplayedChild() == VIEW_CONNECT) {
                showView(VIEW_SETTINGS);
            } else {
                showView(VIEW_CONNECT);
            }
        });
        btnRight.setOnClickListener(v -> showView(VIEW_MONITOR));

        // System back returns to Connect from a sub-view (works with predictive back on targetSdk 36,
        // where the legacy onBackPressed() override is no longer invoked).
        backCallback = new androidx.activity.OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                showView(VIEW_CONNECT);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    private void showView(int index) {
        viewFlipper.setDisplayedChild(index);
        if (backCallback != null) backCallback.setEnabled(index != VIEW_CONNECT);
        boolean connect = index == VIEW_CONNECT;
        btnLeft.setImageResource(connect ? R.drawable.ic_gear_six : R.drawable.ic_arrow_left);
        btnLeft.setContentDescription(getString(connect ? R.string.cd_settings : R.string.cd_back));
        btnRight.setVisibility(connect ? View.VISIBLE : View.INVISIBLE);
        switch (index) {
            case VIEW_MONITOR -> {
                titleText.setText(R.string.nav_monitor);
                subtitleText.setVisibility(View.GONE);
            }
            case VIEW_SETTINGS -> {
                titleText.setText(R.string.nav_settings);
                subtitleText.setVisibility(View.GONE);
            }
            case VIEW_SETUP -> {
                titleText.setText(R.string.setup_title);
                subtitleText.setVisibility(View.GONE);
                renderSetup();
            }
            default -> {
                titleText.setText(R.string.app_name);
                subtitleText.setVisibility(View.VISIBLE);
                refreshState();
            }
        }
    }

    private void bindConnect() {
        powerOrb = findViewById(R.id.powerOrb);
        statusLine = findViewById(R.id.statusLine);
        statusSub = findViewById(R.id.statusSub);
        connectBanner = findViewById(R.id.connectBanner);
        bannerText = findViewById(R.id.bannerText);
        tileSpeed = findViewById(R.id.tileSpeed);
        tileAccuracy = findViewById(R.id.tileAccuracy);
        tileSats = findViewById(R.id.tileSats);
        tileFresh = findViewById(R.id.tileFresh);
        navDot = findViewById(R.id.navDot);
        navTitle = findViewById(R.id.navTitle);
        navDetail = findViewById(R.id.navDetail);

        setText(tileSpeed, R.id.tileLabel, getString(R.string.tile_speed));
        setText(tileAccuracy, R.id.tileLabel, getString(R.string.tile_accuracy));
        setText(tileSats, R.id.tileLabel, getString(R.string.stat_satellites));
        setText(tileFresh, R.id.tileLabel, getString(R.string.tile_fresh));

        powerOrb.setOnClickListener(v -> togglePower());
        connectBanner.setOnClickListener(v -> {
            if (bannerAction != null) bannerAction.run();
        });
        findViewById(R.id.bannerDismiss).setOnClickListener(v -> {
            if (bannerIsUpdate) updateBannerDismissed = true;   // don't bring it back this session
            connectBanner.setVisibility(View.GONE);
        });
    }

    private void bindMonitor() {
        monLocation = findViewById(R.id.monLocation);
        monAltAcc = findViewById(R.id.monAltAcc);
        logText = findViewById(R.id.logText);
        logDot = findViewById(R.id.logDot);
        logText.setMovementMethod(new android.text.method.ScrollingMovementMethod());

        setText(R.id.kvProvider, R.id.kvKey, getString(R.string.label_provider));
        setText(R.id.kvSatellites, R.id.kvKey, getString(R.string.stat_satellites));
        setText(R.id.kvSpeed, R.id.kvKey, getString(R.string.label_speed));
        setText(R.id.kvBearing, R.id.kvKey, getString(R.string.label_bearing));
        setText(R.id.kvAge, R.id.kvKey, getString(R.string.label_age));
        setText(R.id.kvFixes, R.id.kvKey, getString(R.string.label_fixes));

        setText(R.id.lhPackets, R.id.metricLabel, getString(R.string.lh_packets));
        setText(R.id.lhBytes, R.id.metricLabel, getString(R.string.lh_bytes));
        setText(R.id.lhMaxGap, R.id.metricLabel, getString(R.string.lh_max_gap));
        setText(R.id.lhAgeMean, R.id.metricLabel, getString(R.string.lh_age_mean));
        setText(R.id.lhAgeP95, R.id.metricLabel, getString(R.string.lh_age_p95));
        setText(R.id.lhCpu, R.id.metricLabel, getString(R.string.lh_cpu));

        CompoundButton liveSwitch = findViewById(R.id.monitorLiveSwitch);
        liveSwitch.setChecked(liveMonitoring);
        liveSwitch.setOnCheckedChangeListener((b, checked) -> {
            liveMonitoring = checked;
            Preferences.setLiveMonitoring(this, checked);
        });

        View sparkAge = findViewById(R.id.sparkAge);
        View sparkPkt = findViewById(R.id.sparkPkt);
        View sparkSat = findViewById(R.id.sparkSat);
        setText(sparkAge, R.id.sparkLabel, getString(R.string.spark_fix_age));
        setText(sparkPkt, R.id.sparkLabel, getString(R.string.spark_packets));
        setText(sparkSat, R.id.sparkLabel, getString(R.string.spark_satellites));
        sparkAgeView = sparkAge.findViewById(R.id.sparkView);
        sparkPktView = sparkPkt.findViewById(R.id.sparkView);
        sparkSatView = sparkSat.findViewById(R.id.sparkView);
        sparkAgeVal = sparkAge.findViewById(R.id.sparkValue);
        sparkPktVal = sparkPkt.findViewById(R.id.sparkValue);
        sparkSatVal = sparkSat.findViewById(R.id.sparkValue);

        findViewById(R.id.logClear).setOnClickListener(v -> {
            AppLog.clearRing();
            renderLog();
        });
        TextView exportBtn = findViewById(R.id.btnExportLogs);
        exportBtn.setText(R.string.export_all_short);
        exportBtn.setOnClickListener(v -> exportAllData());
        findViewById(R.id.btnExportCsv).setVisibility(View.GONE);
    }

    private void bindSettings() {
        // Permissions
        bindActionButton(R.id.rowPermissions, "",
                getString(R.string.permission_fine_location) + " · " + getString(R.string.permission_coarse_location),
                getString(R.string.request_permissions_short), this::requestPermissions);

        bindActionChevron(R.id.rowSetup, getString(R.string.setup_row), getString(R.string.setup_row_sub),
                () -> showView(VIEW_SETUP));
        setText(R.id.viewSetup, R.id.setupIntro, getString(R.string.setup_intro));
        findViewById(R.id.btnSetupDone).setOnClickListener(v -> showView(VIEW_CONNECT));

        // Display
        bindUiScaleRow();

        // Automation
        bindToggle(R.id.rowAutostartWifi, getString(R.string.autostart_wifi),
                getString(R.string.autostart_wifi_sub), Preferences.autostartWifi(this),
                checked -> {
                    Preferences.setAutostartWifi(this, checked);
                    if (checked) {
                        if (GNSSClientService.isServiceEnabled(this)) AutostartScheduler.schedule(this);
                    } else {
                        AutostartScheduler.cancel(this);
                    }
                });
        bindToggle(R.id.rowAutostartBoot, getString(R.string.autostart_boot),
                getString(R.string.autostart_boot_sub), Preferences.autostartBoot(this),
                checked -> Preferences.setAutostartBoot(this, checked));
        bindActionChevron(R.id.rowA11yAutostart, getString(R.string.a11y_autostart), null,
                this::openAccessibilitySettings);
        bindToggle(R.id.rowStaticJitter, getString(R.string.static_jitter), null,
                Preferences.staticJitterEnabled(this),
                checked -> Preferences.setStaticJitterEnabled(this, checked));

        // Smoothing: a style preset, the full parameter list behind "Advanced", and a reset
        bindFilterPresetRow();
        bindActionChevron(R.id.rowFilterAdvanced, getString(R.string.filter_advanced),
                getString(R.string.filter_advanced_sub), () -> setFilterAdvancedVisible(
                        findViewById(R.id.cardFilterAdvanced).getVisibility() != View.VISIBLE));
        bindActionChevron(R.id.rowFilterReset, getString(R.string.filter_reset),
                getString(R.string.filter_reset_sub), this::confirmFilterReset);
        bindToggle(R.id.rowReportUncertainty, getString(R.string.filter_report_uncertainty),
                getString(R.string.filter_report_uncertainty_sub), Preferences.filterReportUncertainty(this),
                checked -> Preferences.setFilterReportUncertainty(this, checked));
        bindToggle(R.id.rowLatencyComp, getString(R.string.filter_latency_comp),
                getString(R.string.filter_latency_comp_sub), Preferences.filterLatencyCompensation(this),
                checked -> Preferences.setFilterLatencyCompensation(this, checked));
        bindNumberInput(R.id.rowExtraLatency, getString(R.string.filter_extra_latency),
                Preferences.filterExtraLatencyMs(this), 0, 1000,
                v -> Preferences.setFilterExtraLatencyMs(this, (float) v));
        bindToggle(R.id.rowNetworkDelay, getString(R.string.filter_network_delay_comp),
                getString(R.string.filter_network_delay_comp_sub), Preferences.filterNetworkDelay(this),
                checked -> Preferences.setFilterNetworkDelay(this, checked));
        bindToggle(R.id.rowWifiLowLatency, getString(R.string.wifi_low_latency),
                getString(R.string.wifi_low_latency_sub), Preferences.wifiLowLatency(this),
                checked -> Preferences.setWifiLowLatency(this, checked));
        bindToggle(R.id.rowTurnModel, getString(R.string.filter_turn_model),
                getString(R.string.filter_turn_model_sub), Preferences.filterTurnModel(this),
                checked -> Preferences.setFilterTurnModel(this, checked));
        bindNumberInput(R.id.rowTurnResponsiveness, getString(R.string.filter_turn_responsiveness),
                Preferences.filterTurnResponsiveness(this), 0.1, 1,
                v -> { Preferences.setFilterTurnResponsiveness(this, (float) v); refreshFilterPresetSub(); });
        bindToggle(R.id.rowBearingComp, getString(R.string.filter_bearing_comp),
                getString(R.string.filter_bearing_comp_sub), Preferences.filterBearingCompensation(this),
                checked -> Preferences.setFilterBearingCompensation(this, checked));
        bindNumberInput(R.id.rowMinBearingAcc, getString(R.string.filter_min_bearing_acc),
                Preferences.filterMinBearingAccuracy(this), 0, 20,
                v -> Preferences.setFilterMinBearingAccuracy(this, (float) v));
        bindToggle(R.id.rowAdaptivePosition, getString(R.string.filter_adaptive_position),
                getString(R.string.filter_adaptive_position_sub), Preferences.filterAdaptivePosition(this),
                checked -> Preferences.setFilterAdaptivePosition(this, checked));
        bindToggle(R.id.rowGating, getString(R.string.filter_gating),
                getString(R.string.filter_gating_sub), Preferences.filterGating(this),
                checked -> Preferences.setFilterGating(this, checked));
        bindNumberInput(R.id.rowGateThreshold, getString(R.string.filter_gate_threshold),
                Preferences.filterGateThreshold(this), 4, 100,
                v -> { Preferences.setFilterGateThreshold(this, (float) v); refreshFilterPresetSub(); });
        bindToggle(R.id.rowStandstillHold, getString(R.string.filter_standstill),
                getString(R.string.filter_standstill_sub), Preferences.filterStandstillHold(this),
                checked -> Preferences.setFilterStandstillHold(this, checked));
        bindNumberInput(R.id.rowStandstillSpeed, getString(R.string.filter_standstill_speed),
                Preferences.filterStandstillSpeed(this), 0.1, 3,
                v -> { Preferences.setFilterStandstillSpeed(this, (float) v); refreshFilterPresetSub(); });
        bindToggle(R.id.rowAdaptiveNoise, getString(R.string.filter_adaptive_noise),
                getString(R.string.filter_adaptive_noise_sub), Preferences.filterAdaptiveNoise(this),
                checked -> Preferences.setFilterAdaptiveNoise(this, checked));
        bindNumberInput(R.id.rowProcessNoise, getString(R.string.filter_process_noise_base),
                Preferences.filterProcessNoise(this), 0.2, 10,
                v -> { Preferences.setFilterProcessNoise(this, (float) v); refreshFilterPresetSub(); });

        // Diagnostics
        bindToggle(R.id.rowDebug, getString(R.string.debug_logging), null,
                Preferences.debugLoggingEnabled(this),
                checked -> {
                    Preferences.setDebugLoggingEnabled(this, checked);
                    AppLog.setDebug(checked);
                });
        bindToggle(R.id.rowMetrics, getString(R.string.metrics_enabled), null,
                Preferences.metricsEnabled(this),
                checked -> Preferences.setMetricsEnabled(this, checked));
        bindActionChevron(R.id.rowExportAll, getString(R.string.export_all),
                getString(R.string.export_all_sub), this::exportAllData);
        bindToggle(R.id.rowTrackRecording, getString(R.string.track_recording),
                getString(R.string.track_recording_sub), Preferences.trackRecording(this),
                checked -> Preferences.setTrackRecording(this, checked));
        bindActionChevron(R.id.rowClearLogs, getString(R.string.clear_logs),
                getString(R.string.clear_logs_sub), this::confirmClearLogs);

        // About
        String buildLabel = getString(R.string.build_label);
        String shown = buildLabel.isEmpty() ? appVersion : buildLabel;
        bindAction(R.id.rowVersion, String.format(getString(R.string.version_label), shown),
                getString(R.string.about_protocol), false, null);
        // Local, branch and CI builds aren't releases (different signature, placeholder version):
        // only release-workflow builds look for updates.
        updateRow = new UpdateRow(this, findViewById(R.id.rowUpdate), "locsync-client-", appVersion,
                getResources().getBoolean(R.bool.release_build), this::refreshState);
        updateRow.autoCheck();
        bindActionChevron(R.id.rowLicense, getString(R.string.license_gpl3),
                getString(R.string.license_view),
                () -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html"))));
    }

    private void bindFilterPresetRow() {
        FilterPreset preset = FilterPreset.current(this);
        bindAction(R.id.rowFilterPreset, getString(R.string.filter_preset),
                getString(R.string.filter_preset_sub, presetName(preset)), true, () -> {
                    FilterPreset.next(FilterPreset.current(this)).apply(this);
                    recreate();   // the parameter rows below show the new values
                });
    }

    /** Hand-editing a preset parameter turns the Style row into "Custom" (or back) at once. */
    private void refreshFilterPresetSub() {
        setText(R.id.rowFilterPreset, R.id.row_sub,
                getString(R.string.filter_preset_sub, presetName(FilterPreset.current(this))));
    }

    private String presetName(FilterPreset p) {
        if (p == null) return getString(R.string.filter_preset_custom);
        return switch (p) {
            case SMOOTH -> getString(R.string.filter_preset_smooth);
            case BALANCED -> getString(R.string.filter_preset_balanced);
            case RESPONSIVE -> getString(R.string.filter_preset_responsive);
        };
    }

    private void setFilterAdvancedVisible(boolean visible) {
        findViewById(R.id.cardFilterAdvanced).setVisibility(visible ? View.VISIBLE : View.GONE);
        View chevron = findViewById(R.id.rowFilterAdvanced).findViewById(R.id.row_chevron);
        chevron.setRotation(visible ? 90 : 0);
    }

    private final Runnable disarmFilterReset = this::disarmFilterReset;

    private void confirmFilterReset() {
        View row = findViewById(R.id.rowFilterReset);
        setText(row, R.id.row_sub, getString(R.string.filter_reset_confirm));
        TextView button = row.findViewById(R.id.row_button);
        button.setText(R.string.filter_reset_action);
        button.setVisibility(View.VISIBLE);
        row.findViewById(R.id.row_chevron).setVisibility(View.GONE);
        button.setOnClickListener(v -> {
            uiHandler.removeCallbacks(disarmFilterReset);
            Preferences.resetFilterSettings(this);
            Toast.makeText(this, R.string.filter_reset_done, Toast.LENGTH_SHORT).show();
            recreate();
        });
        uiHandler.removeCallbacks(disarmFilterReset);
        uiHandler.postDelayed(disarmFilterReset, 5000);
    }

    private void disarmFilterReset() {
        uiHandler.removeCallbacks(disarmFilterReset);
        View row = findViewById(R.id.rowFilterReset);
        setText(row, R.id.row_sub, getString(R.string.filter_reset_sub));
        row.findViewById(R.id.row_button).setVisibility(View.GONE);
        row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
    }

    /** Tapping cycles through the scales; the activity is recreated so the new size shows at once. */
    private void bindUiScaleRow() {
        float chosen = Preferences.uiScale(this);
        int sw = getApplicationContext().getResources().getConfiguration().smallestScreenWidthDp;
        String value = chosen > 0 ? fmtScale(chosen)
                : getString(R.string.ui_scale_auto, fmtScale(effectiveUiScale(0, sw)));
        bindAction(R.id.rowUiScale, getString(R.string.ui_scale),
                getString(R.string.ui_scale_sub, value), true, () -> {
                    int i = 0;
                    while (i < UI_SCALES.length && UI_SCALES[i] != chosen) i++;
                    Preferences.setUiScale(this, UI_SCALES[(i + 1) % UI_SCALES.length]);
                    recreate();
                });
    }

    private static final java.text.DecimalFormat SCALE_FORMAT =
            new java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols.getInstance(Locale.US));

    private static String fmtScale(float s) {
        return "×" + SCALE_FORMAT.format(s);
    }

    /** The setup checklist with each step's current state (re-checked when returning to the app). */
    private void renderSetup() {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean connected = currentState() == LinkState.CONNECTED;
        List<SetupChecklist.Step> steps = List.of(
                new SetupChecklist.Step(getString(R.string.setup_location),
                        getString(R.string.setup_location_sub),
                        missingPermissionNames().isEmpty(), false, this::requestPermissions),
                new SetupChecklist.Step(getString(R.string.setup_mock),
                        getString(R.string.nav_mock_detail), isMockAppSelected(), false,
                        this::openMockLocationSettings),
                new SetupChecklist.Step(getString(R.string.setup_connect),
                        getString(R.string.setup_connect_sub), connected, false, () -> {
                            if (!GNSSClientService.isServiceRunning()) {
                                // Not startGNSSService(): its battery prompt would open on top of Wi-Fi
                                // settings (battery has its own step).
                                Preferences.setServiceEnabled(this, true);
                                startForegroundService(new Intent(this, GNSSClientService.class));
                            }
                            openSettingsScreen(Settings.ACTION_WIFI_SETTINGS);
                        }),
                new SetupChecklist.Step(getString(R.string.setup_a11y),
                        getString(R.string.setup_a11y_sub), AutostartAccessibilityService.isEnabled(this), true,
                        this::openAccessibilitySettings),
                new SetupChecklist.Step(getString(R.string.setup_battery),
                        getString(R.string.setup_battery_sub),
                        pm == null || pm.isIgnoringBatteryOptimizations(getPackageName()), true,
                        this::ensureBatteryOptimizationExemption));
        SetupChecklist.render(findViewById(R.id.setupSteps), steps);
    }

    /** Head-unit ROMs often lack (or lock) system screens: fall back to Settings, then just say so. */
    private void openSettingsScreen(String action) {
        for (String a : new String[]{action, Settings.ACTION_SETTINGS}) {
            try {
                startActivity(new Intent(a));
                return;
            } catch (RuntimeException e) {   // ActivityNotFoundException, SecurityException
                Log.w(TAG, "Cannot open " + a, e);
            }
        }
        Toast.makeText(this, R.string.setup_no_screen, Toast.LENGTH_LONG).show();
    }

    private void refreshA11yAutostartRow() {
        View row = findViewById(R.id.rowA11yAutostart);
        TextView sub = row.findViewById(R.id.row_sub);
        sub.setText(AutostartAccessibilityService.isEnabled(this)
                ? R.string.a11y_autostart_on : R.string.a11y_autostart_off);
        sub.setVisibility(View.VISIBLE);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            // Some head-unit ROMs strip the accessibility screen; fall back to the settings root.
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void registerReceivers() {
        registerReceiver(connectionReceiver, new IntentFilter("goodvin.locsync.CONNECTION_CHANGED"), RECEIVER_NOT_EXPORTED);
        registerReceiver(locationReceiver, new IntentFilter("goodvin.locsync.LOCATION_UPDATE"), RECEIVER_NOT_EXPORTED);
        registerReceiver(mockLocationStatusReceiver, new IntentFilter("goodvin.locsync.MOCK_LOCATION_STATUS"), RECEIVER_NOT_EXPORTED);
        registerReceiver(metricsReceiver, new IntentFilter("goodvin.locsync.METRICS"), RECEIVER_NOT_EXPORTED);
        registerReceiver(filterStatsReceiver, new IntentFilter("goodvin.locsync.FILTER_STATS"), RECEIVER_NOT_EXPORTED);
    }

    // --- power / state ---

    private void togglePower() {
        if (GNSSClientService.isServiceRunning()) {
            stopGNSSService();
        } else {
            startGNSSService();
        }
        refreshState();
    }

    private LinkState currentState() {
        boolean running = GNSSClientService.isServiceRunning();
        boolean connected = GNSSClientService.getConnectionState() == ConnectionManager.ConnectionState.CONNECTED;
        return LinkState.of(running, connected);
    }

    private void refreshState() {
        LinkState state = currentState();
        powerOrb.setState(state);

        if (state == LinkState.CONNECTED) {
            if (connectedSinceElapsed == 0) connectedSinceElapsed = SystemClock.elapsedRealtime();
        } else {
            connectedSinceElapsed = 0;
        }

        switch (state) {
            case CONNECTED -> {
                statusLine.setText(R.string.status_client_connected);
                statusLine.setTextColor(getColor(R.color.ls_accent_400));
                statusSub.setText(connectedSub());
            }
            case WAITING -> {
                statusLine.setText(R.string.status_client_connecting);
                statusLine.setTextColor(getColor(R.color.ls_accent_300));
                statusSub.setText(R.string.sub_client_waiting);
            }
            default -> {
                statusLine.setText(R.string.status_hint_start);
                statusLine.setTextColor(getColor(R.color.ls_text_muted));
                statusSub.setText(R.string.subtitle_client);
            }
        }
        subtitleText.setText(state == LinkState.CONNECTED ? getString(R.string.sub_client_connected) : statusSub.getText());
        updateBanner(state);
        updateConnectReadouts();
        if (viewFlipper.getDisplayedChild() == VIEW_SETUP) renderSetup();
    }

    private void updateBanner(LinkState state) {
        String msg = null;
        Runnable action = null;

        List<String> missing = missingPermissionNames();
        if (!missing.isEmpty()) {
            // First launch / revoked: name the exact permissions and let a tap request them.
            msg = String.format(getString(R.string.missing_permissions), String.join(", ", missing));
            action = this::requestPermissions;
        } else if (warningMessage != null && mockError) {
            // Version mismatch or a mock-provider failure reported by the service.
            msg = warningMessage;
            action = this::openMockLocationSettings;
        } else if (!isMockAppSelected()) {
            msg = getString(R.string.mock_app_not_selected);
            action = this::openMockLocationSettings;
        }
        UpdateChecker.Release update = updateRow != null ? updateRow.available() : null;
        bannerIsUpdate = msg == null && update != null && !updateBannerDismissed;
        if (bannerIsUpdate) {
            msg = getString(R.string.update_banner, update.tag());
            action = updateRow::install;
        }

        bannerAction = action;
        if (msg != null) {
            bannerText.setText(msg);
            connectBanner.setVisibility(View.VISIBLE);
        } else {
            connectBanner.setVisibility(View.GONE);
        }
    }

    private List<String> missingPermissionNames() {
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(getPermissionName(p));
            }
        }
        return missing;
    }

    private void openMockLocationSettings() {
        try {
            mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            try {
                mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Log.w(TAG, "Cannot open settings", e);
            }
        }
    }

    private String uptime() {
        long s = connectedSinceElapsed == 0 ? 0 : (SystemClock.elapsedRealtime() - connectedSinceElapsed) / 1000;
        return String.format(getString(R.string.uptime_format), s / 60, s % 60);
    }

    // The mock-app check is an AppOps call into the system; the main screen refreshes every second,
    // so cache the answer briefly (onResume, e.g. back from Developer options, re-checks at once).
    private static final long MOCK_CHECK_TTL_MS = 5000;
    private boolean mockAppSelected;
    private long mockCheckedElapsed = 0;

    private boolean isMockAppSelected() {
        long now = SystemClock.elapsedRealtime();
        if (mockCheckedElapsed == 0 || now - mockCheckedElapsed > MOCK_CHECK_TTL_MS) {
            mockAppSelected = MockLocationManager.isSelectedMockApp(this);
            mockCheckedElapsed = now;
        }
        return mockAppSelected;
    }

    /** "Phone 192.168.43.1 · online 03:12" under the status line while connected. */
    private String connectedSub() {
        String addr = GNSSClientService.getServerAddress();
        return getString(R.string.sub_client_connected_fmt,
                addr != null ? addr : getString(R.string.unknown), uptime());
    }

    // A fix older than this (phone-side age + time since it arrived) is no longer "live"; matches the
    // service's GPS-loss cap, after which it stops moving the icon.
    private static final double STALE_FIX_S = 3.0;
    private static final long OUTPUT_ACTIVE_MS = 1500;

    /** Seconds since the phone took the last fix we know of (NaN if none yet). */
    private double fixAgeSeconds() {
        if (lastLocation == null || lastLocationRecvElapsed == 0) return Double.NaN;
        return lastLocationAge + (SystemClock.elapsedRealtime() - lastLocationRecvElapsed) / 1000.0;
    }

    /**
     * Main screen: one status card answering "does the navigator get a position?" and four tiles with
     * what the phone reports (speed, accuracy, satellites, data freshness), each with a plain hint.
     */
    private void updateConnectReadouts() {
        LinkState state = currentState();
        boolean connected = state == LinkState.CONNECTED;
        int textColor = getColor(R.color.ls_text);
        int dimColor = getColor(R.color.ls_text_dim);
        double age = fixAgeSeconds();
        boolean fresh = connected && !Double.isNaN(age) && age <= STALE_FIX_S;
        Location loc = connected ? lastLocation : null;

        // Navigator status
        boolean mockSelected = isMockAppSelected();
        boolean injecting = System.currentTimeMillis() - GNSSClientService.getLastUpdateTime() < OUTPUT_ACTIVE_MS;
        int dotColor;
        if (!mockSelected) {
            navTitle.setText(R.string.nav_mock_title);
            navDetail.setText(R.string.nav_mock_detail);
            dotColor = getColor(R.color.ls_error);
        } else if (connected && fresh && injecting) {
            navTitle.setText(R.string.nav_ok_title);
            navDetail.setText(R.string.nav_ok_detail);
            dotColor = getColor(R.color.ls_accent_400);
        } else if (connected) {
            navTitle.setText(R.string.nav_stale_title);
            navDetail.setText(R.string.nav_stale_detail);
            dotColor = getColor(R.color.ls_error);
        } else if (state == LinkState.WAITING) {
            navTitle.setText(R.string.nav_waiting_title);
            navDetail.setText(R.string.nav_waiting_detail);
            dotColor = getColor(R.color.ls_accent_300);
        } else {
            navTitle.setText(R.string.nav_off_title);
            navDetail.setText(R.string.nav_off_detail);
            dotColor = getColor(R.color.ls_neutral_500);
        }
        navDot.getBackground().mutate().setTint(dotColor);
        findViewById(R.id.navCard).setOnClickListener(mockSelected ? null : v -> openMockLocationSettings());

        updatePhoneCard(connected);
        updateCarCard();

        String none = getString(R.string.value_none);
        if (loc == null) {
            String hint = connected ? getString(R.string.hint_no_fix) : "";
            setTile(tileSpeed, none, "", hint, dimColor);
            setTile(tileAccuracy, none, "", hint, dimColor);
            setTile(tileSats, none, "", hint, dimColor);
            setTile(tileFresh, none, "", hint, dimColor);
            return;
        }

        // Speed (Doppler, from the phone)
        if (loc.hasSpeed()) {
            float kmh = loc.getSpeed() * 3.6f;
            setTile(tileSpeed, String.format(Locale.US, "%.0f", kmh), getString(R.string.unit_kmh),
                    getString(kmh < 2 ? R.string.hint_speed_stopped : R.string.hint_speed), textColor);
        } else {
            setTile(tileSpeed, none, getString(R.string.unit_kmh), getString(R.string.hint_speed_none), dimColor);
        }

        // Accuracy (68% radius reported by the phone) with a plain-language grade
        if (loc.hasAccuracy()) {
            float acc = loc.getAccuracy();
            int grade = acc <= 5 ? R.string.acc_excellent : acc <= 10 ? R.string.acc_good
                    : acc <= 25 ? R.string.acc_fair : R.string.acc_poor;
            setTile(tileAccuracy, String.format(Locale.US, "±%.0f", acc), getString(R.string.unit_m),
                    getString(grade), acc <= 25 ? textColor : getColor(R.color.ls_error));
        } else {
            setTile(tileAccuracy, none, "", "", dimColor);
        }

        // Satellites used by the phone, and which provider produced the fix
        String provider = lastProvider == null ? "" : lastProvider;
        String providerHint = "fused".equals(provider) ? getString(R.string.hint_provider_fused)
                : "gps".equals(provider) ? getString(R.string.hint_provider_gps)
                : provider.isEmpty() ? "" : provider;
        setTile(tileSats, String.valueOf(lastSatellites), "", providerHint,
                lastSatellites > 0 ? textColor : getColor(R.color.ls_error));

        // Freshness of the data
        setTile(tileFresh, String.format(Locale.US, "%.1f", age), getString(R.string.unit_s_ago),
                getString(fresh ? R.string.hint_fresh : R.string.hint_stale),
                fresh ? textColor : getColor(R.color.ls_error));
    }

    /** "Phone: 78%, charging · 36 °C" and a warning when it overheats or runs flat. */
    private void updatePhoneCard(boolean connected) {
        View card = findViewById(R.id.phoneCard);
        LocationProto.PhoneState phone = connected ? GNSSClientService.getPhoneState() : null;
        if (phone == null) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        float temp = phone.hasBatteryTempC() ? phone.getBatteryTempC() : Float.NaN;
        int percent = phone.hasBatteryPercent() ? phone.getBatteryPercent() : -1;
        StringBuilder title = new StringBuilder(percent < 0 ? getString(R.string.phone_title)
                : getString(phone.getCharging() ? R.string.phone_battery_charging : R.string.phone_battery, percent));
        // Floor, so 44.6 °C doesn't read "45 °C" while the card still says it's fine (hot is ≥ 45).
        if (!Float.isNaN(temp)) title.append(" · ").append((int) Math.floor(temp)).append(" °C");
        TextView titleView = findViewById(R.id.phoneTitle);
        titleView.setText(title);
        PhoneHealth health = PhoneHealth.of(percent, phone.getCharging(), temp,
                phone.getThermalStatus());
        TextView detail = findViewById(R.id.phoneDetail);
        detail.setText(switch (health) {
            case HOT -> R.string.phone_hot;
            case LOW_BATTERY -> R.string.phone_low_battery;
            default -> R.string.phone_ok;
        });
        findViewById(R.id.phoneDot).getBackground().mutate().setTint(getColor(
                health == PhoneHealth.OK ? R.color.ls_accent_400 : R.color.ls_error));
    }

    /** "Car: 54 km/h · D" with "Fuel 62% · 480 km · out 7 °C" — the head unit's own signals. */
    private void updateCarCard() {
        View card = findViewById(R.id.carCard);
        VehicleData.Snapshot s = vehicleData == null ? null : vehicleData.snapshot();
        if (s == null || !s.hasAny()) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);

        // Title: Motion (speed, gear).
        StringBuilder title = new StringBuilder(getString(R.string.car_title));
        if (s.speedKmh != null) {
            title.append("  ").append(String.format(Locale.US, "%.0f", s.speedKmh))
                    .append(' ').append(getString(R.string.unit_kmh));
        }
        if (s.gearRaw != null) {
            title.append("  ").append(getString(R.string.car_gear_fmt, s.gearRaw));
        }
        ((TextView) findViewById(R.id.carTitle)).setText(title);

        // Detail: Energy + Temperatures, " · "-joined.
        StringBuilder detail = new StringBuilder();
        if (s.fuelPercent != null) {
            append(detail, getString(R.string.car_fuel_fmt, Math.round(s.fuelPercent)));
        }
        if (s.rangeKm != null) {
            append(detail, getString(R.string.car_range_fmt, Math.round(s.rangeKm)));
        }
        if (s.outsideTempC != null) {
            append(detail, getString(R.string.car_outside_fmt, Math.round(s.outsideTempC)));
        }
        if (s.coolantTempC != null) {
            append(detail, getString(R.string.car_coolant_fmt, Math.round(s.coolantTempC)));
        }
        ((TextView) findViewById(R.id.carDetail)).setText(detail.toString());
    }

    private static void append(StringBuilder sb, String piece) {
        if (sb.length() > 0) sb.append(" · ");
        sb.append(piece);
    }

    private void setTile(View tile, String value, String unit, String hint, int valueColor) {
        TextView v = tile.findViewById(R.id.tileValue);
        v.setText(value);
        v.setTextColor(valueColor);
        setText(tile, R.id.tileUnit, unit);
        setText(tile, R.id.tileHint, hint);
    }

    // --- monitor readouts ---

    private void updateMonitorLocation() {
        boolean connected = currentState() == LinkState.CONNECTED;
        if (!connected || lastLocation == null) {
            monLocation.setText(R.string.value_none);
            monAltAcc.setText("");
            String none = getString(R.string.value_none);
            setText(R.id.kvProvider, R.id.kvValue, none);
            setText(R.id.kvSatellites, R.id.kvValue, none);
            setText(R.id.kvSpeed, R.id.kvValue, none);
            setText(R.id.kvBearing, R.id.kvValue, none);
            setText(R.id.kvAge, R.id.kvValue, none);
            setText(R.id.kvFixes, R.id.kvValue, none);
            return;
        }
        Location loc = lastLocation;
        monLocation.setText(String.format(getString(R.string.location_format), loc.getLatitude(), loc.getLongitude()));
        StringBuilder alt = new StringBuilder();
        if (loc.hasAltitude()) alt.append(String.format(getString(R.string.altitude_format), loc.getAltitude()));
        if (loc.hasAccuracy()) alt.append(String.format(getString(R.string.location_accuracy_format), loc.getAccuracy()));
        monAltAcc.setText(alt.toString().trim());
        setText(R.id.kvProvider, R.id.kvValue, lastProvider != null ? lastProvider : getString(R.string.unknown));
        setText(R.id.kvSatellites, R.id.kvValue, String.valueOf(lastSatellites));
        setText(R.id.kvSpeed, R.id.kvValue, loc.hasSpeed() ? String.format(getString(R.string.speed_format), loc.getSpeed()) : getString(R.string.value_none));
        setText(R.id.kvBearing, R.id.kvValue, loc.hasBearing() ? String.format(getString(R.string.bearing_format), loc.getBearing()) : getString(R.string.value_none));
        setText(R.id.kvAge, R.id.kvValue, String.format(getString(R.string.age_format), lastLocationAge));
        setText(R.id.kvFixes, R.id.kvValue, Double.isNaN(mFixes) ? getString(R.string.value_none) : String.format(getString(R.string.fixes_format), mFixes));
    }

    private void updateMonitorMetrics() {
        boolean connected = currentState() == LinkState.CONNECTED;
        setText(R.id.lhPackets, R.id.metricValue, connected ? fmt1(mPktRecv) : none());
        setText(R.id.lhBytes, R.id.metricValue, connected ? fmt0(mBytesRecv) : none());
        setText(R.id.lhMaxGap, R.id.metricValue, connected ? fmt0(mMaxGap) : none());
        setText(R.id.lhAgeMean, R.id.metricValue, connected ? fmt0(mAgeMean) : none());
        setText(R.id.lhAgeP95, R.id.metricValue, connected ? fmt0(mAgeP95) : none());
        setText(R.id.lhCpu, R.id.metricValue, fmt0(mCpu));
        sparkAgeVal.setText(fmt0(mAgeMean));
        sparkPktVal.setText(fmt1(mPktRecv));
        sparkSatVal.setText(String.valueOf(lastSatellites));
    }

    /** Fills the Monitor filter card with the rows the service sent (reusing cells when possible). */
    private void renderFilterStats(String[] labels, String[] values) {
        if (labels == null || values == null || labels.length != values.length) return;
        android.view.ViewGroup rows = findViewById(R.id.monFilterRows);
        if (rows.getChildCount() != labels.length) {
            rows.removeAllViews();
            android.view.LayoutInflater inflater = getLayoutInflater();
            for (int i = 0; i < labels.length; i++) {
                View cell = inflater.inflate(R.layout.ls_kv_cell, rows, false);
                cell.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
                rows.addView(cell);
            }
        }
        for (int i = 0; i < labels.length; i++) {
            View cell = rows.getChildAt(i);
            setText(cell, R.id.kvKey, labels[i]);
            setText(cell, R.id.kvValue, values[i]);
        }
        findViewById(R.id.monFilterCard).setVisibility(View.VISIBLE);
    }

    private void renderLog() {
        List<AppLog.Entry> entries = AppLog.snapshot();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int max = Math.min(entries.size(), 120);
        for (int i = 0; i < max; i++) {
            AppLog.Entry e = entries.get(i);
            int start = sb.length();
            sb.append(logTime.format(e.timeMillis)).append(' ');
            span(sb, start, sb.length(), getColor(R.color.ls_text_dim));
            int ls = sb.length();
            sb.append(e.level).append(' ');
            span(sb, ls, sb.length(), levelColor(e.level));
            sb.append(e.message).append('\n');
        }
        logText.setText(sb);
    }

    private int levelColor(char level) {
        return switch (level) {
            case 'I' -> getColor(R.color.ls_accent_400);
            case 'W' -> getColor(R.color.ls_error);
            default -> getColor(R.color.ls_log_debug);
        };
    }

    private void span(SpannableStringBuilder sb, int start, int end, int color) {
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // --- periodic tick ---

    private void startUIUpdates() {
        uiHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (currentState() == LinkState.CONNECTED) {
                    statusSub.setText(connectedSub());
                }
                if (viewFlipper.getDisplayedChild() == VIEW_CONNECT) {
                    updateConnectReadouts();   // freshness and the navigator status age every second
                }
                blinkLogDot();
                if (liveMonitoring && viewFlipper.getDisplayedChild() == VIEW_MONITOR) {
                    renderLog();
                }
                uiHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private ObjectAnimator dotAnimator;
    private void blinkLogDot() {
        boolean running = GNSSClientService.isServiceRunning();
        if (running && dotAnimator == null) {
            dotAnimator = ObjectAnimator.ofFloat(logDot, "alpha", 1f, 0.2f);
            dotAnimator.setDuration(1400);
            dotAnimator.setRepeatCount(ObjectAnimator.INFINITE);
            dotAnimator.setRepeatMode(ObjectAnimator.REVERSE);
            dotAnimator.start();
        } else if (!running && dotAnimator != null) {
            dotAnimator.cancel();
            dotAnimator = null;
            logDot.setAlpha(0.3f);
        }
    }

    // --- permissions (behaviour preserved) ---

    private void refreshPermissions() {
        boolean allGranted = true;
        List<String> missing = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                missing.add(getPermissionName(p));
            }
        }
        View row = findViewById(R.id.rowPermissions);
        TextView label = row.findViewById(R.id.row_label);
        TextView button = row.findViewById(R.id.row_button);
        if (allGranted) {
            label.setText(R.string.all_permissions_granted);
            button.setVisibility(View.GONE);
        } else {
            label.setText(String.format(getString(R.string.missing_permissions), String.join(", ", missing)));
            button.setVisibility(View.VISIBLE);
        }
        updateBanner(currentState());
    }

    private String getPermissionName(String permission) {
        return switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION -> getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION -> getString(R.string.permission_coarse_location);
            default -> permission.substring(permission.lastIndexOf('.') + 1);
        };
    }

    private void requestPermissions() {
        List<String> toRequest = new ArrayList<>();
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (toRequest.isEmpty()) {
            checkMockLocationSettings();
        } else {
            permissionLauncher.launch(toRequest.toArray(new String[0]));
        }
    }

    private void checkMockLocationSettings() {
        Toast.makeText(this, getString(R.string.mock_location_enable_message), Toast.LENGTH_LONG).show();
        try {
            mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            mockLocationSettingsLauncher.launch(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // --- service control (behaviour preserved) ---

    private void startGNSSService() {
        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        startForegroundService(serviceIntent);
        Preferences.setServiceEnabled(this, true);
        if (Preferences.autostartWifi(this)) {
            AutostartScheduler.schedule(this);
        }
        ensureBatteryOptimizationExemption();
    }

    private void stopGNSSService() {
        // Pause only: stop the running instance but keep the service "enabled" and the Wi-Fi/boot
        // autostart job armed, so it comes back on the next trigger (Wi-Fi connect, reboot, relaunch).
        // Autostart is disabled instead via the Automation toggle.
        stopService(new Intent(this, GNSSClientService.class));
        connectedSinceElapsed = 0;
    }

    @SuppressLint("BatteryLife")
    private void ensureBatteryOptimizationExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Log.w(TAG, "Battery optimization exemption request failed", e);
        }
    }

    // --- exports (behaviour preserved) ---

    private final Runnable disarmClearLogs = this::disarmClearLogs;

    /**
     * First tap on "Clear logs" arms an inline confirmation (a "Clear" button in the row) instead of
     * an AlertDialog: some head-unit ROMs restyle system dialogs so their buttons are invisible.
     * Disarms by itself after a few seconds.
     */
    private void confirmClearLogs() {
        View row = findViewById(R.id.rowClearLogs);
        setText(row, R.id.row_sub, getString(R.string.clear_logs_confirm));
        TextView button = row.findViewById(R.id.row_button);
        button.setText(R.string.clear_logs_action);
        button.setVisibility(View.VISIBLE);
        row.findViewById(R.id.row_chevron).setVisibility(View.GONE);
        button.setOnClickListener(v -> {
            disarmClearLogs();
            long freed = LogExporter.clearAll(this);
            renderLog();
            refreshTrackRow();
            Toast.makeText(this, getString(R.string.clear_logs_done,
                    android.text.format.Formatter.formatShortFileSize(this, freed)), Toast.LENGTH_SHORT).show();
        });
        uiHandler.removeCallbacks(disarmClearLogs);
        uiHandler.postDelayed(disarmClearLogs, 5000);
    }

    private void disarmClearLogs() {
        uiHandler.removeCallbacks(disarmClearLogs);
        View row = findViewById(R.id.rowClearLogs);
        setText(row, R.id.row_sub, getString(R.string.clear_logs_sub));
        row.findViewById(R.id.row_button).setVisibility(View.GONE);
        row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
    }

    /** Settings → Diagnostics / Monitor: one archive with logs, metrics, track and settings. */
    private void exportAllData() {
        Toast.makeText(this, R.string.export_all_in_progress, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File zip = null;
            try {
                zip = LogExporter.exportAll(this, "locsync-client", appVersion, Preferences.dump(this));
            } catch (Exception e) {
                Log.e(TAG, "Error exporting data", e);
            }
            final File result = zip;
            runOnUiThread(() -> {
                if (result != null) {
                    shareFile(result, "application/zip", getString(R.string.export_all));
                } else {
                    Toast.makeText(this, R.string.export_all_failed, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private File trackFile() {
        return TrackRecorder.fileFor(new File(getCacheDir(), "logs"));
    }

    /** Track toggle subtitle: what it records, plus how much is recorded so far. */
    private void refreshTrackRow() {
        File f = trackFile();
        TextView sub = findViewById(R.id.rowTrackRecording).findViewById(R.id.row_sub);
        String base = getString(R.string.track_recording_sub);
        sub.setText(f.exists() && f.length() > 0
                ? base + String.format(Locale.US, " · %.1f MB", f.length() / 1048576.0) : base);
        sub.setVisibility(View.VISIBLE);
    }

    private void shareFile(File file, String mime, String chooserTitle) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, chooserTitle));
        } catch (Exception e) {
            Log.w(TAG, "Failed to share file", e);
        }
    }

    // --- small view helpers ---

    private String none() { return getString(R.string.value_none); }

    private static String fmt0(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.0f", v);
    }

    private static String fmt1(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.1f", v);
    }

    private void setText(int rootId, int childId, CharSequence text) {
        setText(findViewById(rootId), childId, text);
    }

    private void setText(View root, int childId, CharSequence text) {
        TextView tv = root.findViewById(childId);
        if (tv != null) tv.setText(text);
    }

    private void bindToggle(int rowId, String label, String sub, boolean checked,
                            java.util.function.Consumer<Boolean> onChange) {
        SettingsRows.bindToggle(findViewById(rowId), label, sub, checked, onChange);
    }

    private void bindNumberInput(int rowId, String label, double value, double min, double max,
                                 java.util.function.DoubleConsumer onChange) {
        SettingsRows.bindNumber(findViewById(rowId), label, value, min, max, onChange);
    }

    private void bindAction(int rowId, String label, String sub, boolean chevron, Runnable click) {
        SettingsRows.bindAction(findViewById(rowId), label, sub, chevron, click);
    }

    private void bindActionChevron(int rowId, String label, String sub, Runnable click) {
        bindAction(rowId, label, sub, true, click);
    }

    private void bindActionButton(int rowId, String label, String sub, String buttonLabel, Runnable buttonClick) {
        SettingsRows.bindActionButton(findViewById(rowId), label, sub, buttonLabel, buttonClick);
    }
}

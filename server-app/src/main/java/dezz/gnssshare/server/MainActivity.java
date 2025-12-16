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

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dezz.gnssshare.shared.LogExporter;
import dezz.gnssshare.shared.VersionGetter;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GNSSServerActivity";

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 1002;

    // Required permissions for the GNSS server
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    };

    private Button requestPermissionsButton;
    private Button startServiceButton;
    private Button stopServiceButton;
    private TextView statusText;
    private TextView permissionsStatusText;
    private TextView technicalDetailsText;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable fillInterfaceListRunnable = new Runnable() {
        @Override
        public void run() {
            fillInterfaceList();
            mainHandler.postDelayed(this, 1000);
        }
    };

    private String appVersion = "<unknown>";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        setContentView(R.layout.activity_main_server);

        appVersion = VersionGetter.getAppVersionName(this);

        initializeViews();

        if (GNSSServerService.isServiceEnabled(this) && !GNSSServerService.isServiceRunning()) {
            startGNSSService();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        updateUIState(GNSSServerService.isServiceEnabled(this));
        updatePermissionsStatus();

        mainHandler.post(this.fillInterfaceListRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();

        mainHandler.removeCallbacks(this.fillInterfaceListRunnable);
    }

    private void initializeViews() {
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        statusText = findViewById(R.id.statusText);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        technicalDetailsText = findViewById(R.id.technical_details);

        TextView header = findViewById(R.id.header);
        header.setText(String.format("%s %s", getString(R.string.app_name), appVersion));

        requestPermissionsButton.setOnClickListener(v -> requestPermissions());
        startServiceButton.setOnClickListener(v -> startGNSSService());
        stopServiceButton.setOnClickListener(v -> stopGNSSService());
        findViewById(R.id.exportLogsButton).setOnClickListener(v -> exportLogs("gnss-server"));
    }

    private void fillInterfaceList() {
        StringBuilder sb = new StringBuilder();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                String name = intf.getDisplayName();
                if (!name.startsWith("wlan")) {
                    continue;
                }

                boolean nameWasShown = false;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }
                    String sAddr = addr.getHostAddress();
                    if (sAddr == null || sAddr.contains(":")) {
                        continue;
                    }
                    if (!nameWasShown) {
                        String displayName = switch (name) {
                            case "wlan0" ->
                                    String.format("%s (%s)", name, getString(R.string.interface_wifi));
                            case "wlan1" ->
                                    String.format("%s (%s)", name, getString(R.string.interface_hotspot));
                            default -> name;
                        };
                        sb.append("  • ");
                        sb.append(displayName);
                        sb.append(":\n");
                        nameWasShown = true;
                    }
                    sb.append("    - ");
                    sb.append(sAddr);
                    sb.append("\n");
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to fill interface list", e);
        }

        if (sb.length() == 0) {
            sb.append("  ");
            sb.append(getString(R.string.interface_none));
            sb.append("\n");
        }

        technicalDetailsText.setText(String.format(getString(R.string.technical_details), sb));
    }

    public void startGNSSService() {
        // Mark service as permanently enabled
        GNSSServerService.setServiceEnabled(this, true);

        Intent serviceIntent = new Intent(this, GNSSServerService.class);
        startForegroundService(serviceIntent);

        updateUIState(true);
    }

    public void stopGNSSService() {
        // Mark service as permanently disabled
        GNSSServerService.setServiceEnabled(this, false);

        Intent serviceIntent = new Intent(this, GNSSServerService.class);
        stopService(serviceIntent);

        updateUIState(false);
    }

    private void updateUIState(boolean serviceRunning) {
        if (serviceRunning) {
            startServiceButton.setEnabled(false);
            stopServiceButton.setEnabled(true);
            statusText.setText(R.string.service_running);
            statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
        } else {
            startServiceButton.setEnabled(true);
            stopServiceButton.setEnabled(false);
            statusText.setText(R.string.service_stopped);
            statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
        }
    }

    private void requestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        // Check which permissions are missing
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (permissionsToRequest.isEmpty()) {
            // All permissions already granted, check battery optimization
            checkBatteryOptimization();
        } else {
            // Request missing permissions
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @SuppressLint("BatteryLife")
    private void checkBatteryOptimization() {
        Intent intent = new Intent();
        String packageName = getPackageName();
        if (!Settings.System.canWrite(this)) {
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + packageName));
            startActivityForResult(intent, BATTERY_OPTIMIZATION_REQUEST_CODE);
        } else {
            updatePermissionsStatus();
        }
    }

    private void updatePermissionsStatus() {
        boolean allPermissionsGranted = true;
        List<String> missingPermissions = new ArrayList<>();

        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                missingPermissions.add(getPermissionName(permission));
            }
        }

        // TODO: Check [PowerManager.isIgnoringBatteryOptimizations(packageName)]

        if (allPermissionsGranted) {
            permissionsStatusText.setText(R.string.all_permissions_granted);
            permissionsStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            requestPermissionsButton.setVisibility(View.GONE);
        } else {
            String statusText = String.format(getString(R.string.missing_permissions), String.join(", ", missingPermissions));
            permissionsStatusText.setText(statusText);
            permissionsStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
            requestPermissionsButton.setVisibility(View.VISIBLE);
        }
    }

    private String getPermissionName(String permission) {
        return switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION ->
                    getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION ->
                    getString(R.string.permission_coarse_location);
            case Manifest.permission.ACCESS_BACKGROUND_LOCATION ->
                    getString(R.string.permission_background_location);
            default -> permission.substring(permission.lastIndexOf('.') + 1);
        };
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, R.string.all_permissions_granted_toast, Toast.LENGTH_SHORT).show();
                checkBatteryOptimization();
            } else {
                Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                updatePermissionsStatus();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == BATTERY_OPTIMIZATION_REQUEST_CODE) {
            updatePermissionsStatus();
        }
    }

    /**
     * Export logs to a file and share it
     */
    private void exportLogs(String appName) {
        // Show progress
        Toast.makeText(this, dezz.gnssshare.logexporter.R.string.export_logs_in_progress, Toast.LENGTH_SHORT).show();

        // Run in background to avoid blocking UI
        new Thread(() -> {
            try {
                // Export logs to a file
                File logFile = LogExporter.exportLogs(this, appName);

                // Clean up old logs
                LogExporter.cleanupOldLogs(this, appName);

                // Update UI on main thread
                runOnUiThread(() -> {
                    if (logFile != null) {
                        // Share the log file
                        shareLogFile(logFile);
                        Toast.makeText(this, dezz.gnssshare.logexporter.R.string.export_logs_success, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, dezz.gnssshare.logexporter.R.string.export_logs_no_logs, Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error exporting logs", e);
                runOnUiThread(() ->
                        Toast.makeText(this,
                                String.format(getString(dezz.gnssshare.logexporter.R.string.export_logs_error), e.getMessage()),
                                Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }

    /**
     * Share the log file using an intent
     */
    private void shareLogFile(File logFile) {
        try {
            // Get URI using FileProvider
            Uri fileUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    logFile
            );

            // Create share intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Start the share activity
            startActivity(Intent.createChooser(
                    shareIntent,
                    getString(dezz.gnssshare.logexporter.R.string.share_logs)
            ));
        } catch (Exception e) {
            Log.e(TAG, "Error sharing log file", e);
            Toast.makeText(this,
                    String.format(getString(dezz.gnssshare.logexporter.R.string.export_logs_error), e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }
}

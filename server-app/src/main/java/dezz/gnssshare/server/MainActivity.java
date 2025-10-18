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
import android.app.ActivityManager;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GNSSServerActivity";

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int BATTERY_OPTIMIZATION_REQUEST_CODE = 1002;

    // Required permissions for the GNSS server
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.WAKE_LOCK
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_main_server);

        initializeViews();
        setupClickListeners();

        mainHandler.post(this.fillInterfaceListRunnable);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(this.fillInterfaceListRunnable);
        super.onDestroy();
    }

    private void initializeViews() {
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        statusText = findViewById(R.id.statusText);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        technicalDetailsText = findViewById(R.id.technical_details);

        // Initialize UI state based on actual service status
        updateUIState(isServiceActuallyRunning());

        // Check permissions status on startup
        updatePermissionsStatus();

        fillInterfaceList();
    }

    private void setupClickListeners() {
        requestPermissionsButton.setOnClickListener(v -> requestPermissions());
        startServiceButton.setOnClickListener(v -> startGNSSService());
        stopServiceButton.setOnClickListener(v -> stopGNSSService());
    }

    private void fillInterfaceList() {
        StringBuilder sb = new StringBuilder();
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (!intf.getDisplayName().startsWith("wlan")) {
                    continue;
                }
                String name = intf.getDisplayName();
                String displayName = switch (name) {
                    case "wlan0" -> getString(R.string.interface_wifi);
                    case "wlan1" -> getString(R.string.interface_hotspot);
                    default -> name;
                };

                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                if (addrs.isEmpty()) {
                    continue;
                }
                sb.append("  • ");
                sb.append(displayName);
                sb.append(":\n");
                for (InetAddress addr : addrs) {
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }
                    String sAddr = addr.getHostAddress();
                    if (sAddr != null && !sAddr.contains(":")) {
                        sb.append("    - ");
                        sb.append(sAddr);
                        sb.append("\n");
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to fill interface list", e);
        }

        technicalDetailsText.setText(String.format(getString(R.string.technical_details), sb));
    }

    private void startGNSSService() {
        // Mark service as permanently enabled
        GNSSServerService.setServiceEnabled(this, true);

        Intent serviceIntent = new Intent(this, GNSSServerService.class);
        startForegroundService(serviceIntent);

        updateUIState(true);

        Toast.makeText(this, getString(R.string.toast_service_enabled), Toast.LENGTH_LONG).show();
    }

    private void stopGNSSService() {
        // Mark service as permanently disabled
        GNSSServerService.setServiceEnabled(this, false);

        Intent serviceIntent = new Intent(this, GNSSServerService.class);
        stopService(serviceIntent);

        updateUIState(false);

        Toast.makeText(this, getString(R.string.toast_service_disabled), Toast.LENGTH_LONG).show();
    }

    private void updateUIState(boolean serviceRunning) {
        if (serviceRunning) {
            startServiceButton.setEnabled(false);
            stopServiceButton.setEnabled(true);
            statusText.setText(R.string.service_running);
            statusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
        } else {
            startServiceButton.setEnabled(true);
            stopServiceButton.setEnabled(false);
            statusText.setText(R.string.service_stopped);
            statusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
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

        if (allPermissionsGranted) {
            permissionsStatusText.setText(R.string.all_permissions_granted);
            permissionsStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
            requestPermissionsButton.setVisibility(View.GONE);
        } else {
            String statusText = String.format(getString(R.string.missing_permissions), String.join(", ", missingPermissions));
            permissionsStatusText.setText(statusText);
            permissionsStatusText.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            requestPermissionsButton.setVisibility(View.VISIBLE);
        }
    }

    private String getPermissionName(String permission) {
        return switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION ->
                    getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION ->
                    getString(R.string.permission_coarse_location);
            case Manifest.permission.FOREGROUND_SERVICE ->
                    getString(R.string.permission_foreground_service);
            case Manifest.permission.ACCESS_NETWORK_STATE ->
                    getString(R.string.permission_network_state);
            case Manifest.permission.ACCESS_WIFI_STATE -> getString(R.string.permission_wifi_state);
            case Manifest.permission.CHANGE_WIFI_STATE ->
                    getString(R.string.permission_change_wifi);
            case Manifest.permission.WAKE_LOCK -> getString(R.string.permission_wake_lock);
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

    @Override
    protected void onResume() {
        super.onResume();
        // Update UI state based on actual service status when resuming
        updateUIState(isServiceActuallyRunning());
    }

    private boolean isServiceActuallyRunning() {
        ActivityManager manager = getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (GNSSServerService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}

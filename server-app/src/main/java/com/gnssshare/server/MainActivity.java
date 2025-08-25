// MainActivity.java (Server - Smartphone)
package com.gnssshare.server;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_main_server);

        initializeViews();
        setupClickListeners();
    }

    private void initializeViews() {
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        statusText = findViewById(R.id.statusText);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);

        // Initialize UI state based on actual service status
        updateUIState(isServiceActuallyRunning());

        // Check permissions status on startup
        updatePermissionsStatus();
    }

    private void setupClickListeners() {
        requestPermissionsButton.setOnClickListener(v -> requestPermissions());
        startServiceButton.setOnClickListener(v -> startGNSSService());
        stopServiceButton.setOnClickListener(v -> stopGNSSService());
    }

    private void startGNSSService() {
        Intent serviceIntent = new Intent(this, GNSSServerService.class);
        startForegroundService(serviceIntent);

        // Mark service as permanently enabled
        GNSSServerService.setServiceEnabled(this, true);

        updateUIState(true);

        Toast.makeText(this, getString(R.string.toast_service_enabled), Toast.LENGTH_LONG).show();
    }

    private void stopGNSSService() {
        Intent serviceIntent = new Intent(this, GNSSServerService.class);
        stopService(serviceIntent);

        // Mark service as permanently disabled
        GNSSServerService.setServiceEnabled(this, false);

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
        switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION:
                return getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION:
                return getString(R.string.permission_coarse_location);
            case Manifest.permission.FOREGROUND_SERVICE:
                return getString(R.string.permission_foreground_service);
            case Manifest.permission.ACCESS_NETWORK_STATE:
                return getString(R.string.permission_network_state);
            case Manifest.permission.ACCESS_WIFI_STATE:
                return getString(R.string.permission_wifi_state);
            case Manifest.permission.CHANGE_WIFI_STATE:
                return getString(R.string.permission_change_wifi);
            case Manifest.permission.WAKE_LOCK:
                return getString(R.string.permission_wake_lock);
            default:
                return permission.substring(permission.lastIndexOf('.') + 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
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
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (GNSSServerService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}

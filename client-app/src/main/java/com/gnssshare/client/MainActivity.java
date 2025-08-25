package com.gnssshare.client;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "GNSSClientActivity";

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MOCK_LOCATION_SETTINGS_REQUEST_CODE = 1002;

    // Required permissions for the GNSS client
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS
    };

    private TextView statusText;
    private TextView connectionText;
    private TextView dataAgeText;
    private TextView locationText;
    private TextView satellitesText;
    private TextView providerText;
    private TextView ageText;
    private TextView additionalInfoText;
    private TextView lastUpdateText;
    private Button requestPermissionsButton;
    private TextView permissionsStatusText;
    private TextView mockLocationStatusText;

    private GNSSClientService clientService;
    private boolean serviceBound = false;
    private final Handler uiHandler = new Handler();

    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.gnssshare.CONNECTION_CHANGED".equals(intent.getAction())) {
                boolean connected = intent.getBooleanExtra("connected", false);
                updateConnectionStatus(connected);
            }
        }
    };

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.gnssshare.LOCATION_UPDATE".equals(intent.getAction())) {
                Location location = intent.getParcelableExtra("location");
                int satellites = intent.getIntExtra("satellites", 0);
                String provider = intent.getStringExtra("provider");
                float locationAge = intent.getFloatExtra("locationAge", 0);

                updateLocationInfo(location, satellites, provider, locationAge);
            }
        }
    };

    private final BroadcastReceiver mockLocationStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.gnssshare.MOCK_LOCATION_STATUS".equals(intent.getAction())) {
                String message = intent.getStringExtra("message");
                updateMockLocationStatus(message);
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            GNSSClientService.GNSSClientBinder binder = (GNSSClientService.GNSSClientBinder) service;
            clientService = binder.getService();
            serviceBound = true;

            updateConnectionStatus(clientService.isConnectedToServer());
            updateLocationInfo(
                    clientService.getLastReceivedLocation(),
                    0,
                    null,
                    0
            );
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            clientService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_main);

        initializeViews();
        startAndBindService();
        registerReceivers();

        // Check permissions status on startup
        updatePermissionsStatus();

        // Start periodic UI updates
        startUIUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }

        unregisterReceiver(connectionReceiver);
        unregisterReceiver(locationReceiver);
        unregisterReceiver(mockLocationStatusReceiver);
        uiHandler.removeCallbacksAndMessages(null);
    }

    private void initializeViews() {
        statusText = findViewById(R.id.statusText);
        connectionText = findViewById(R.id.connectionText);
        dataAgeText = findViewById(R.id.dataAgeText);
        locationText = findViewById(R.id.locationText);
        satellitesText = findViewById(R.id.satellitesText);
        providerText = findViewById(R.id.providerText);
        ageText = findViewById(R.id.ageText);
        additionalInfoText = findViewById(R.id.additionalInfoText);
        lastUpdateText = findViewById(R.id.lastUpdateText);
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        mockLocationStatusText = findViewById(R.id.mockLocationStatusText);

        // Initialize with default values
        updateConnectionStatus(false);
        dataAgeText.setText(
                String.format(
                        getString(R.string.data_age_status),
                        getString(R.string.unknown)
                )
        );

        additionalInfoText.setText(
                String.format("%s  %s",
                    String.format(getString(R.string.movement_speed), getString(R.string.unknown)),
                    String.format(getString(R.string.movement_bearing), getString(R.string.unknown))
                )
        );
        lastUpdateText.setText(
                String.format(
                        getString(R.string.movement_last_update),
                        getString(R.string.unknown)
                )
        );

        // Set up permissions button click listener
        requestPermissionsButton.setOnClickListener(v -> requestPermissions());
    }

    private void startAndBindService() {
        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void registerReceivers() {
        IntentFilter connectionFilter = new IntentFilter("com.gnssshare.CONNECTION_CHANGED");
        registerReceiver(connectionReceiver, connectionFilter, RECEIVER_NOT_EXPORTED);

        IntentFilter locationFilter = new IntentFilter("com.gnssshare.LOCATION_UPDATE");
        registerReceiver(locationReceiver, locationFilter, RECEIVER_NOT_EXPORTED);

        IntentFilter mockLocationStatusFilter = new IntentFilter("com.gnssshare.MOCK_LOCATION_STATUS");
        registerReceiver(mockLocationStatusReceiver, mockLocationStatusFilter, RECEIVER_NOT_EXPORTED);
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
            // All permissions already granted, check mock location settings
            checkMockLocationSettings();
        } else {
            // Request missing permissions
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void checkMockLocationSettings() {
        // For mock location, we need to guide user to developer options
        Toast.makeText(this, getString(R.string.mock_location_enable_message), Toast.LENGTH_LONG).show();
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            startActivityForResult(intent, MOCK_LOCATION_SETTINGS_REQUEST_CODE);
        } catch (Exception e) {
            // Fallback to general settings
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            startActivityForResult(intent, MOCK_LOCATION_SETTINGS_REQUEST_CODE);
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
            permissionsStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
            requestPermissionsButton.setVisibility(View.VISIBLE);
        }

        if (clientService != null && clientService.isMockLocationEnabled()) {
            mockLocationStatusText.setVisibility(View.GONE);
        }
    }

    private String getPermissionName(String permission) {
        switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION:
                return getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION:
                return getString(R.string.permission_coarse_location);
            case Manifest.permission.ACCESS_NETWORK_STATE:
                return getString(R.string.permission_network_state);
            case Manifest.permission.ACCESS_WIFI_STATE:
                return getString(R.string.permission_wifi_state);
            case Manifest.permission.CHANGE_WIFI_STATE:
                return getString(R.string.permission_change_wifi);
            case Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS:
                return getString(R.string.permission_location_extra_commands);
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
                checkMockLocationSettings();
            } else {
                Toast.makeText(this, R.string.missing_permissions_toast, Toast.LENGTH_LONG).show();
                updatePermissionsStatus();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == MOCK_LOCATION_SETTINGS_REQUEST_CODE) {
            updatePermissionsStatus();
        }
    }

    private void updateConnectionStatus(boolean connected) {
        runOnUiThread(() -> {
            statusText.setText(
                    String.format(
                            getString(R.string.status_format),
                            getString(R.string.app_name),
                            getString(connected ? R.string.connected : R.string.disconnected)
                    )
            );
            if (connected) {
                connectionText.setText(
                        String.format(getString(R.string.connection_status),
                                getString(R.string.connection_status_connected))
                );
                connectionText.setTextColor(getColor(android.R.color.holo_green_dark));
            } else {
                connectionText.setText(
                        String.format(getString(R.string.connection_status),
                                getString(R.string.connection_status_disconnected))
                );
                connectionText.setTextColor(getColor(android.R.color.holo_red_dark));

                // Clear location info when disconnected
                locationText.setText(String.format(getString(R.string.location_status), getString(R.string.unknown)));
                satellitesText.setText(String.format(getString(R.string.satellites_status), 0));
                providerText.setText(String.format(getString(R.string.provider_status), getString(R.string.unknown)));
                ageText.setText(String.format(getString(R.string.age_status), getString(R.string.unknown)));
            }
        });
    }

    private void updateLocationInfo(Location location, int satellites, String provider, float locationAge) {
        if (location == null) return;

        runOnUiThread(() -> {
            // Location coordinates
            StringBuilder locationBuilder = new StringBuilder();

            locationBuilder.append(
                    String.format(getString(R.string.location_status),
                            String.format(getString(R.string.location_format),
                                    location.getLatitude(),
                                    location.getLongitude()
                            )
                    )
            );

            if (location.hasAltitude()) {
                locationBuilder.append(
                        String.format(getString(R.string.altitude_format), location.getAltitude())
                );
            }

            if (location.hasAccuracy()) {
                locationBuilder.append(
                        String.format(getString(R.string.location_accuracy_format), location.getAccuracy())
                );
            }

            locationText.setText(locationBuilder.toString());

            // Satellites
            satellitesText.setText(String.format(getString(R.string.satellites_status), satellites));

            // Provider
            providerText.setText(
                    String.format(
                            getString(R.string.provider_status),
                            (provider != null ? provider : getString(R.string.unknown))
                    )
            );

            // Age
            ageText.setText(
                    String.format(
                            getString(R.string.age_status),
                            String.format(getString(R.string.age_format), locationAge)
                    )
            );

            // Last update time
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            lastUpdateText.setText(String.format(getString(R.string.movement_last_update), sdf.format(new Date())));

            // Additional info
            StringBuilder additionalInfo = new StringBuilder();
            if (location.hasSpeed()) {
                additionalInfo.append(
                        String.format(getString(R.string.movement_speed),
                                String.format(getString(R.string.speed_format), location.getSpeed())
                        )
                );
            }
            if (location.hasBearing()) {
                if (additionalInfo.length() > 0) {
                    additionalInfo.append("  ");
                }
                additionalInfo.append(
                        String.format(getString(R.string.movement_bearing),
                                String.format(getString(R.string.bearing_format), location.getBearing())
                        )
                );
            }

            if (additionalInfo.length() > 0) {
                additionalInfoText.setText(additionalInfo.toString());
            }
        });
    }

    private void updateMockLocationStatus(String message) {
        runOnUiThread(() -> {
            if (mockLocationStatusText != null) {
                mockLocationStatusText.setText(message);
                mockLocationStatusText.setVisibility(View.VISIBLE);
            }
        });
    }

    private void startUIUpdates() {
        // Update UI every second to show connection age and other dynamic info
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateDynamicInfo();
                uiHandler.postDelayed(this, 1000); // Update every second
            }
        }, 1000);
    }

    private void updateDynamicInfo() {
        if (serviceBound && clientService != null) {
            long lastUpdateTime = clientService.getLastUpdateTime();
            if (lastUpdateTime > 0) {
                long ageSeconds = (System.currentTimeMillis() - lastUpdateTime) / 1000;

                runOnUiThread(() -> {
                    if (dataAgeText != null) {
                        if (ageSeconds < 60) {
                            dataAgeText.setText(
                                    String.format(
                                            getString(R.string.data_age_status),
                                            String.format(
                                                    getString(R.string.data_age_format_s),
                                                    ageSeconds
                                            )
                                    )
                            );
                            dataAgeText.setTextColor(getColor(android.R.color.holo_green_dark));
                        } else {
                            dataAgeText.setText(
                                    String.format(getString(R.string.data_age_status),
                                            String.format(
                                                    getString(R.string.data_age_format_ms), ageSeconds / 60, ageSeconds % 60)
                                    )
                            );
                            dataAgeText.setTextColor(getColor(android.R.color.holo_orange_dark));
                        }
                    }
                });
            }
        }
    }
}

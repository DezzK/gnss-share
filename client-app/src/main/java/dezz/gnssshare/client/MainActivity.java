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

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int MOCK_LOCATION_SETTINGS_REQUEST_CODE = 1002;

    // Required permissions for the GNSS client
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    private TextView statusText;
    private TextView connectionText;
    private TextView dataAgeText;
    private TextView locationText;
    private TextView satellitesText;
    private TextView providerText;
    private TextView ageText;
    private TextView additionalInfoText;
    private View permissionsSection;
    private Button requestPermissionsButton;
    private TextView permissionsStatusText;
    private TextView mockLocationStatusText;
    private Button startServiceButton;
    private Button stopServiceButton;
    private TextView serviceStatusText;
    private TextView serverIpEditLabel;
    private EditText serverIpEdit;

    private final Handler uiHandler = new Handler();

    private final BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("dezz.gnssshare.CONNECTION_CHANGED".equals(intent.getAction())) {
                String stateStr = intent.getStringExtra("state");
                ConnectionManager.ConnectionState state = ConnectionManager.ConnectionState.valueOf(stateStr);
                String serverAddress = intent.getStringExtra("serverAddress");
                updateConnectionStatus(state, serverAddress);
            }
        }
    };

    private final BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("dezz.gnssshare.LOCATION_UPDATE".equals(intent.getAction())) {
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
            if ("dezz.gnssshare.MOCK_LOCATION_STATUS".equals(intent.getAction())) {
                String message = intent.getStringExtra("message");
                boolean error = intent.getBooleanExtra("error", true);
                updateMockLocationStatus(message, error);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Objects.requireNonNull(getSupportActionBar()).hide();
        setContentView(R.layout.activity_main);

        initializeViews();
        registerReceivers();

        // Check permissions status on startup
        updatePermissionsStatus();

        // Start periodic UI updates
        startUIUpdates();

        if (GNSSClientService.isServiceEnabled(this) && !GNSSClientService.isServiceRunning()) {
            startGNSSService();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

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
        permissionsSection = findViewById(R.id.permissionsSection);
        requestPermissionsButton = findViewById(R.id.requestPermissionsButton);
        permissionsStatusText = findViewById(R.id.permissionsStatusText);
        mockLocationStatusText = findViewById(R.id.mockLocationStatusText);
        serverIpEditLabel = findViewById(R.id.serverIpEditLabel);
        serverIpEdit = findViewById(R.id.serverIpEdit);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        serviceStatusText = findViewById(R.id.serviceStatusText);

        // Initialize with default values
        updateConnectionStatus(GNSSClientService.getConnectionState(), GNSSClientService.getServerAddress());
        dataAgeText.setText(String.format(getString(R.string.data_age_status), getString(R.string.unknown)));

        additionalInfoText.setText(
                String.format("%s  %s",
                        String.format(getString(R.string.movement_speed), getString(R.string.unknown)),
                        String.format(getString(R.string.movement_bearing), getString(R.string.unknown))
                )
        );

        boolean useGatewayIp = Preferences.useGatewayIp(this);
        RadioButton connectToGatewayIpRadio = findViewById(R.id.connectToGatewayIpRadioButton);
        RadioButton setIpManuallyRadio = findViewById(R.id.setIpManuallyRadioButton);
        connectToGatewayIpRadio.setChecked(useGatewayIp);
        connectToGatewayIpRadio.setOnClickListener(v -> {
            Preferences.setUseGatewayIp(this, true);
            serverIpEditLabel.setEnabled(false);
            serverIpEdit.setEnabled(false);
        });
        setIpManuallyRadio.setChecked(!useGatewayIp);
        setIpManuallyRadio.setOnClickListener(v -> {
            Preferences.setUseGatewayIp(this, false);
            serverIpEditLabel.setEnabled(true);
            serverIpEdit.setEnabled(true);
        });
        serverIpEditLabel.setEnabled(!useGatewayIp);
        serverIpEdit.setEnabled(!useGatewayIp);
        serverIpEdit.setText(Preferences.serverAddress(this));

        // Set up permissions button click listener
        requestPermissionsButton.setOnClickListener(v -> requestPermissions());

        // Set up service control button click listeners
        startServiceButton.setOnClickListener(v -> startGNSSService());
        stopServiceButton.setOnClickListener(v -> stopGNSSService());

        // Set up server IP edit text change listener
        serverIpEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                Preferences.setServerAddress(MainActivity.this, s.toString());
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        // Initialize service status
        updateServiceStatus(GNSSClientService.isServiceRunning());
    }

    private void registerReceivers() {
        IntentFilter connectionFilter = new IntentFilter("dezz.gnssshare.CONNECTION_CHANGED");
        registerReceiver(connectionReceiver, connectionFilter, RECEIVER_NOT_EXPORTED);

        IntentFilter locationFilter = new IntentFilter("dezz.gnssshare.LOCATION_UPDATE");
        registerReceiver(locationReceiver, locationFilter, RECEIVER_NOT_EXPORTED);

        IntentFilter mockLocationStatusFilter = new IntentFilter("dezz.gnssshare.MOCK_LOCATION_STATUS");
        registerReceiver(mockLocationStatusReceiver, mockLocationStatusFilter, RECEIVER_NOT_EXPORTED);
    }

    private void startGNSSService() {
        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        startForegroundService(serviceIntent);
        updateServiceStatus(true);

        // Update SharedPreferences immediately to reflect the intent to start
        Preferences.setServiceEnabled(this, true);

        Toast.makeText(this, getString(R.string.toast_service_enabled), Toast.LENGTH_LONG).show();
    }

    private void stopGNSSService() {
        // Update SharedPreferences immediately to reflect the intent to stop
        Preferences.setServiceEnabled(this, false);

        Intent serviceIntent = new Intent(this, GNSSClientService.class);
        stopService(serviceIntent);

        updateServiceStatus(false);

        // Reset connection status display
        updateConnectionStatus(ConnectionManager.ConnectionState.DISCONNECTED, null);

        Toast.makeText(this, getString(R.string.toast_service_disabled), Toast.LENGTH_LONG).show();
    }

    private void updateServiceStatus(boolean isServiceRunning) {
        if (isServiceRunning) {
            startServiceButton.setEnabled(false);
            stopServiceButton.setEnabled(true);
            serviceStatusText.setText(R.string.service_running);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_green_light));
        } else {
            startServiceButton.setEnabled(true);
            stopServiceButton.setEnabled(false);
            serviceStatusText.setText(R.string.service_stopped);
            serviceStatusText.setTextColor(getColor(android.R.color.holo_red_light));
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
        updatePermissionsStatus(null, false);
    }

    private void updatePermissionsStatus(String mockLocationsErrorMessage, boolean mockLocationError) {
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
            permissionsStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_light));
            requestPermissionsButton.setVisibility(View.GONE);
        } else {
            String statusText = String.format(getString(R.string.missing_permissions), String.join(", ", missingPermissions));
            permissionsStatusText.setText(statusText);
            permissionsStatusText.setTextColor(getColor(android.R.color.holo_red_light));
            requestPermissionsButton.setVisibility(View.VISIBLE);
        }

        boolean mockLocationEnabled = GNSSClientService.isMockLocationEnabled(getContentResolver());

        if (mockLocationEnabled && !mockLocationError) {
            mockLocationStatusText.setVisibility(View.GONE);
        } else {
            if (mockLocationError) {
                mockLocationStatusText.setText(mockLocationsErrorMessage);
            }
            mockLocationStatusText.setVisibility(View.VISIBLE);
        }

        if (allPermissionsGranted && mockLocationEnabled && !mockLocationError) {
            permissionsSection.setVisibility(View.GONE);
        } else {
            permissionsSection.setVisibility(View.VISIBLE);
        }
    }

    private String getPermissionName(String permission) {
        return switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION ->
                    getString(R.string.permission_fine_location);
            case Manifest.permission.ACCESS_COARSE_LOCATION ->
                    getString(R.string.permission_coarse_location);
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

    private void updateConnectionStatus(ConnectionManager.ConnectionState state, String serverAddress) {
        runOnUiThread(() -> {
            statusText.setText(
                    String.format(
                            getString(R.string.status_format),
                            getString(R.string.app_name),
                            getString(switch (state) {
                                case CONNECTED -> R.string.connected;
                                case CONNECTING -> R.string.connecting;
                                case DISCONNECTED -> R.string.disconnected;
                            })
                    )
            );
            switch (state) {
                case CONNECTED -> {
                    connectionText.setText(
                            String.format(getString(R.string.connection_status),
                                    String.format(getString(R.string.connection_status_connected), serverAddress))
                    );
                    connectionText.setTextColor(getColor(android.R.color.holo_green_light));
                }

                case CONNECTING -> {
                    connectionText.setText(
                            String.format(getString(R.string.connection_status),
                                    String.format(getString(R.string.connection_status_connecting),
                                            serverAddress == null ? getString(R.string.unknown) : serverAddress))
                    );
                    connectionText.setTextColor(getColor(android.R.color.holo_orange_light));
                }

                case DISCONNECTED -> {
                    connectionText.setText(
                            String.format(getString(R.string.connection_status),
                                    getString(R.string.connection_status_disconnected))
                    );
                    connectionText.setTextColor(getColor(android.R.color.holo_red_light));
                }
            }

            if (state != ConnectionManager.ConnectionState.CONNECTED) {
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

    private void updateMockLocationStatus(String message, boolean error) {
        runOnUiThread(() -> updatePermissionsStatus(message, error));
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
        if (!GNSSClientService.isServiceRunning()) {
            return;
        }

        long lastUpdateTime = GNSSClientService.getLastUpdateTime();
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

                    } else {
                        dataAgeText.setText(
                                String.format(getString(R.string.data_age_status),
                                        String.format(
                                                getString(R.string.data_age_format_ms), ageSeconds / 60, ageSeconds % 60)
                                )
                        );
                    }

                    if (ageSeconds < 10) {
                        dataAgeText.setTextColor(getColor(android.R.color.holo_green_light));
                    } else {
                        dataAgeText.setTextColor(getColor(android.R.color.holo_red_light));
                    }
                }
            });
        }
    }
}

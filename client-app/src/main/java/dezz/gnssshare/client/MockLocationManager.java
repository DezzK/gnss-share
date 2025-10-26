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

import androidx.annotation.NonNull;

import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class MockLocationManager {
    private static final String TAG = "MockLocationManager";

    private final LocationManager locationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // We need to use such runnable to make scheduled disabling cancelable
    private final Runnable disableMockLocationProvider = this::disableMockLocationProvider;

    private boolean isMockLocationProviderSetup = false;

    public MockLocationManager(Context context) {
        locationManager = context.getSystemService(LocationManager.class);
    }

    public void startMockLocationProvider() {
        Log.d(TAG, "Starting mock location provider");

        mainHandler.removeCallbacks(this.disableMockLocationProvider);
        setupMockLocationProvider();
        enableMockLocationProvider();
    }

    public void stopMockLocationProvider(long delayMillis) {
        Log.d(TAG, "Scheduling stopping of mock location provider in " + delayMillis + " ms");

        mainHandler.removeCallbacks(this.disableMockLocationProvider);
        mainHandler.postDelayed(this.disableMockLocationProvider, delayMillis);
    }

    public void setMockLocation(@NonNull Location location) {
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location);
    }

    public static boolean isMockLocationEnabled(ContentResolver contentResolver) {
        try {
            return android.provider.Settings.Secure.getString(contentResolver, "mock_location") != null;
        } catch (Exception e) {
            Log.e(TAG, "Error checking mock location setting", e);
            return false;
        }
    }

    public synchronized void shutdown() {
        Log.d(TAG, "Shutdown");

        if (!isMockLocationProviderSetup) {
            return;
        }

        mainHandler.removeCallbacks(this.disableMockLocationProvider);
        disableMockLocationProvider();
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (IllegalArgumentException e) {
            // Provider doesn't exist, which is fine
        }
        isMockLocationProviderSetup = false;
    }

    private synchronized void setupMockLocationProvider() {
        if (isMockLocationProviderSetup) {
            return;
        }

        // Remove existing test provider if it exists
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER);
        } catch (IllegalArgumentException e) {
            // Provider doesn't exist, which is fine
            Log.d(TAG, "GPS test provider doesn't exist, creating new one");
        }

        // Add test provider with correct parameters
        locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, // requiresNetwork - GPS doesn't require network
                true,  // requiresSatellite - GPS uses satellites
                false, // requiresCell - GPS doesn't require cell
                false, // hasMonetaryCost - GPS is free
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                ProviderProperties.POWER_USAGE_HIGH, // powerRequirement
                ProviderProperties.ACCURACY_FINE // accuracy
        );

        isMockLocationProviderSetup = true;

        Log.i(TAG, "Mock location provider setup successfully");
    }

    private void enableMockLocationProvider() {
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
        Log.d(TAG, "Mock location provider enabled");
    }

    private void disableMockLocationProvider() {
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false);
        Log.d(TAG, "Mock location provider disabled");
    }
}

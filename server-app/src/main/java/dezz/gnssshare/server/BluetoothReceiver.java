/*
 * Copyright © 2026 Dezz (https://github.com/DezzK)
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

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


import java.util.HashSet;
import java.util.Set;

/**
 * BroadcastReceiver for Bluetooth connection events.
 * Handles auto-start/stop of GNSS service based on Bluetooth device connections.
 * Supports multiple trigger devices — service starts when any registered device
 * connects and stops only when all registered devices are disconnected.
 */
public class BluetoothReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothReceiver";

    // Tracks which registered trigger devices are currently connected (in-memory).
    // Reset on process death, which is acceptable — we'll get fresh ACL events.
    private static final Set<String> connectedTriggerDevices = new HashSet<>();

    /** Returns true if no registered trigger devices are currently connected. */
    public static boolean allTriggerDevicesDisconnected() {
        return connectedTriggerDevices.isEmpty();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        Log.d(TAG, "Received Bluetooth action: " + action);

        // Check if Bluetooth auto-start feature is enabled
        if (!Preferences.bluetoothAutoStartEnabled(context)) {
            Log.d(TAG, "Bluetooth auto-start is disabled, ignoring event");
            return;
        }

        // Check if any trigger devices are configured
        if (!Preferences.hasBluetoothTriggerDevices(context)) {
            Log.d(TAG, "No trigger devices configured, ignoring event");
            return;
        }

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) {
            Log.w(TAG, "No device in intent");
            return;
        }

        String deviceMac = device.getAddress();
        String deviceName = device.getName();
        Log.d(TAG, "Device: " + deviceName + " (" + deviceMac + ")");

        // Check if this is one of the trigger devices
        if (!Preferences.isBluetoothTriggerDevice(context, deviceMac)) {
            Log.d(TAG, "Not a trigger device, ignoring");
            return;
        }

        switch (action) {
            case BluetoothDevice.ACTION_ACL_CONNECTED:
                handleDeviceConnected(context, deviceMac, deviceName);
                break;

            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                handleDeviceDisconnected(context, deviceMac, deviceName);
                break;

            default:
                Log.d(TAG, "Unhandled action: " + action);
        }
    }

    private void handleDeviceConnected(Context context, String deviceMac, String deviceName) {
        Log.i(TAG, "Trigger device connected: " + deviceName + " (" + deviceMac + ")");
        connectedTriggerDevices.add(deviceMac);

        if (GNSSServerService.isServiceRunning()) {
            // Cancel any pending auto-stop (don't mark as BT-managed — service may have been started manually)
            GNSSServerService.cancelBluetoothAutoStopRequest();
        } else {
            // Start the service with BT flag
            Log.i(TAG, "Starting GNSS service due to Bluetooth connection");
            GNSSServerService.setServiceEnabled(context, true);
            Intent serviceIntent = new Intent(context, GNSSServerService.class);
            serviceIntent.putExtra(GNSSServerService.EXTRA_STARTED_BY_BLUETOOTH, true);
            context.startForegroundService(serviceIntent);
        }
    }

    private void handleDeviceDisconnected(Context context, String deviceMac, String deviceName) {
        Log.i(TAG, "Trigger device disconnected: " + deviceName + " (" + deviceMac + ")");
        connectedTriggerDevices.remove(deviceMac);

        if (!connectedTriggerDevices.isEmpty()) {
            Log.d(TAG, "Other trigger devices still connected: " + connectedTriggerDevices.size() + ", not stopping");
            return;
        }

        // All trigger devices disconnected — let the service decide whether to schedule auto-stop
        GNSSServerService.evaluateAutoStop();
    }
}

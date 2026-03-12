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

/**
 * BroadcastReceiver for Bluetooth connection events.
 * Handles auto-start/stop of GNSS service based on Bluetooth device connections.
 */
public class BluetoothReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothReceiver";

    public static final String ACTION_BT_DISCONNECT = "dezz.gnssshare.server.BT_DISCONNECT";
    public static final String ACTION_BT_CONNECT = "dezz.gnssshare.server.BT_CONNECT";

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

        // Get the trigger device MAC address
        String triggerDeviceMac = Preferences.bluetoothTriggerDeviceMac(context);
        if (triggerDeviceMac == null || triggerDeviceMac.isEmpty()) {
            Log.d(TAG, "No trigger device configured, ignoring event");
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

        // Check if this is the trigger device
        if (!triggerDeviceMac.equals(deviceMac)) {
            Log.d(TAG, "Not the trigger device, ignoring");
            return;
        }

        switch (action) {
            case BluetoothDevice.ACTION_ACL_CONNECTED:
                handleDeviceConnected(context, deviceName);
                break;

            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                handleDeviceDisconnected(context, deviceName);
                break;

            default:
                Log.d(TAG, "Unhandled action: " + action);
        }
    }

    private void handleDeviceConnected(Context context, String deviceName) {
        Log.i(TAG, "Trigger device connected: " + deviceName);

        if (GNSSServerService.isServiceRunning()) {
            // Cancel any pending auto-stop
            Intent cancelIntent = new Intent(context, GNSSServerService.class);
            cancelIntent.setAction(ACTION_BT_CONNECT);
            context.startService(cancelIntent);
        } else {
            // Start the service
            Log.i(TAG, "Starting GNSS service due to Bluetooth connection");
            GNSSServerService.setServiceEnabled(context, true);
            Intent serviceIntent = new Intent(context, GNSSServerService.class);
            context.startForegroundService(serviceIntent);
        }
    }

    private void handleDeviceDisconnected(Context context, String deviceName) {
        Log.i(TAG, "Trigger device disconnected: " + deviceName);

        // Only schedule auto-stop if service is running
        if (!GNSSServerService.isServiceRunning()) {
            Log.d(TAG, "Service not running, nothing to stop");
            return;
        }

        // Send intent to service to schedule auto-stop
        Intent stopIntent = new Intent(context, GNSSServerService.class);
        stopIntent.setAction(ACTION_BT_DISCONNECT);
        context.startService(stopIntent);
    }
}
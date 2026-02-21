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

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Helper class for managing app preferences.
 * Stores Bluetooth auto-start settings and other configuration.
 */
public class Preferences {
    private static final String PREF_BLUETOOTH_AUTO_START_ENABLED = "bluetoothAutoStartEnabled";
    private static final String PREF_BLUETOOTH_TRIGGER_DEVICE_MAC = "bluetoothTriggerDeviceMac";
    private static final String PREF_BLUETOOTH_TRIGGER_DEVICE_NAME = "bluetoothTriggerDeviceName";

    // Bluetooth Auto-Start Enabled
    public static void setBluetoothAutoStartEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_BLUETOOTH_AUTO_START_ENABLED, enabled).apply();
    }

    public static boolean bluetoothAutoStartEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_BLUETOOTH_AUTO_START_ENABLED, false);
    }

    // Bluetooth Trigger Device MAC Address
    public static void setBluetoothTriggerDeviceMac(Context context, String macAddress) {
        getPrefs(context).edit().putString(PREF_BLUETOOTH_TRIGGER_DEVICE_MAC, macAddress).apply();
    }

    public static String bluetoothTriggerDeviceMac(Context context) {
        return getPrefs(context).getString(PREF_BLUETOOTH_TRIGGER_DEVICE_MAC, null);
    }

    // Bluetooth Trigger Device Name
    public static void setBluetoothTriggerDeviceName(Context context, String name) {
        getPrefs(context).edit().putString(PREF_BLUETOOTH_TRIGGER_DEVICE_NAME, name).apply();
    }

    public static String bluetoothTriggerDeviceName(Context context) {
        return getPrefs(context).getString(PREF_BLUETOOTH_TRIGGER_DEVICE_NAME, null);
    }

    // Convenience method to set both MAC and name at once
    public static void setBluetoothTriggerDevice(Context context, String macAddress, String name) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.putString(PREF_BLUETOOTH_TRIGGER_DEVICE_MAC, macAddress);
        editor.putString(PREF_BLUETOOTH_TRIGGER_DEVICE_NAME, name);
        editor.apply();
    }

    // Clear Bluetooth trigger device
    public static void clearBluetoothTriggerDevice(Context context) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        editor.remove(PREF_BLUETOOTH_TRIGGER_DEVICE_MAC);
        editor.remove(PREF_BLUETOOTH_TRIGGER_DEVICE_NAME);
        editor.apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
}
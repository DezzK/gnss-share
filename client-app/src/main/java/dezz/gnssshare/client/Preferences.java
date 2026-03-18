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

package dezz.gnssshare.client;

import android.content.Context;
import android.content.SharedPreferences;

public class Preferences {
    private static final String PREF_IS_SERVICE_ENABLED = "isServiceEnabled";
    private static final String PREF_USE_GATEWAY_IP = "useGatewayIp";
    private static final String PREF_SERVER_ADDRESS = "serverAddress";
    private static final String PREF_STATIC_JITTER_ENABLED = "staticJitterEnabled";

    // SharedPreferences helper methods
    public static void setServiceEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_IS_SERVICE_ENABLED, enabled).apply();
    }

    public static boolean serviceEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_IS_SERVICE_ENABLED, false);
    }

    public static void setUseGatewayIp(Context context, boolean value) {
        getPrefs(context).edit().putBoolean(PREF_USE_GATEWAY_IP, value).apply();
    }

    public static boolean useGatewayIp(Context context) {
        return getPrefs(context).getBoolean(PREF_USE_GATEWAY_IP, true);
    }

    public static void setServerAddress(Context context, String value) {
        getPrefs(context).edit().putString(PREF_SERVER_ADDRESS, value).apply();
    }

    public static String serverAddress(Context context) {
        return getPrefs(context).getString(PREF_SERVER_ADDRESS, "192.168.43.1");
    }

    public static void setStaticJitterEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_STATIC_JITTER_ENABLED, enabled).apply();
    }

    public static boolean staticJitterEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_STATIC_JITTER_ENABLED, false);
    }

    private static SharedPreferences getPrefs(Context context) {
        final Context deviceContext = context.getApplicationContext().createDeviceProtectedStorageContext();
        return deviceContext.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
}

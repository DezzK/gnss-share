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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GNSSBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Device boot completed, checking if GNSS server should auto-start");

            // Check if the service was previously enabled
            if (GNSSServerService.isServiceEnabled(context)) {
                if (GNSSServerService.isServiceRunning()) {
                    Log.i(TAG, "GNSS server service is already running. Don't start it again.");
                    return;
                }

                Log.i(TAG, "Auto-starting GNSS server service");
                Intent serviceIntent = new Intent(context, GNSSServerService.class);
                context.startForegroundService(serviceIntent);
            } else {
                Log.d(TAG, "GNSS server service not enabled for auto-start");
            }
        }
    }
}

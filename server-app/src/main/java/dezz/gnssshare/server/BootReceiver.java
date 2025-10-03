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

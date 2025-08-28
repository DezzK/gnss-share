package com.gnssshare.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "GNSSClientBootReceiver";

    private static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())
                || ACTION_QUICKBOOT_POWERON.equals(intent.getAction())) {
            Log.d(TAG, "Device boot completed, checking if GNSS client should auto-start");

            // Check if service was previously enabled
            if (GNSSClientService.isServiceEnabled(context)) {
                Log.i(TAG, "Auto-starting GNSS client service");
                Intent serviceIntent = new Intent(context, GNSSClientService.class);
                context.startForegroundService(serviceIntent);
            } else {
                Log.d(TAG, "GNSS client service not enabled for auto-start");
            }
        }
    }
}
package dezz.gnssshare.server;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

public class StopActivity extends android.app.Activity {

    private static final String TAG = "StopActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (GNSSServerService.isServiceRunning()) {
            // Mark service as permanently disabled
            Context app = getApplicationContext();
            GNSSServerService.setServiceEnabled(app, false);
            Intent serviceIntent = new Intent(app, GNSSServerService.class);
            Log.d(TAG, "Stop GNSSServerService");
            stopService(serviceIntent);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }
}

package dezz.gnssshare.server;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

public class StartActivity extends android.app.Activity {

    private static final String TAG = "StartActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!GNSSServerService.isServiceRunning()) {
            Context app = getApplicationContext();
            // Mark service as permanently enabled
            GNSSServerService.setServiceEnabled(app, true);
            Intent serviceIntent = new Intent(app, GNSSServerService.class);
            Log.d(TAG, "Start GNSSServerService");
            startForegroundService(serviceIntent);
        }
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }
}

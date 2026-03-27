package com.rdevzph.speedtesttriggerandroid;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

public class NotificationActionReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        Intent serviceIntent = new Intent(context, SpeedtestForegroundService.class);
        serviceIntent.setAction(intent.getAction());

        if (SpeedtestForegroundService.ACTION_START.equals(intent.getAction())) {
            serviceIntent.putExtra(
                    SpeedtestForegroundService.EXTRA_INTERVAL,
                    intent.getIntExtra(SpeedtestForegroundService.EXTRA_INTERVAL, 30)
            );
            serviceIntent.putExtra(
                    SpeedtestForegroundService.EXTRA_NO_DOWNLOAD,
                    intent.getBooleanExtra(SpeedtestForegroundService.EXTRA_NO_DOWNLOAD, false)
            );
            serviceIntent.putExtra(
                    SpeedtestForegroundService.EXTRA_NO_UPLOAD,
                    intent.getBooleanExtra(SpeedtestForegroundService.EXTRA_NO_UPLOAD, true)
            );
            ContextCompat.startForegroundService(context, serviceIntent);
            return;
        }

        context.startService(serviceIntent);
    }
}
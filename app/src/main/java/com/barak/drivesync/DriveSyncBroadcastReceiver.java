package com.barak.drivesync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class DriveSyncBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (DriveSyncMessagingService.ACTION_SYNC.equals(intent.getAction())) {
            String changeType = intent.getStringExtra(DriveSyncMessagingService.EXTRA_CHANGE_TYPE);
            String fileName = intent.getStringExtra(DriveSyncMessagingService.EXTRA_FILE_NAME);
            String modified = intent.getStringExtra(DriveSyncMessagingService.EXTRA_MODIFIED);

            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchIntent.putExtra(DriveSyncMessagingService.EXTRA_CHANGE_TYPE, changeType);
            launchIntent.putExtra(DriveSyncMessagingService.EXTRA_FILE_NAME, fileName);
            launchIntent.putExtra(DriveSyncMessagingService.EXTRA_MODIFIED, modified);
            launchIntent.putExtra("trigger_sync", true);

            context.startActivity(launchIntent);
        }
    }
}
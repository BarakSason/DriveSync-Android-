package com.barak.drivesync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class DriveSyncMessagingService extends FirebaseMessagingService {
    private static final String TAG = "DriveSyncMsgService";
    private static final String CHANNEL_ID = "drive_sync_channel";
    private static final String GROUP_KEY_DRIVE_SYNC = "com.barak.drivesync.DRIVE_SYNC_GROUP";
    public static final String ACTION_SYNC = "com.barak.drivesync.ACTION_SYNC";
    public static final String EXTRA_CHANGE_TYPE = "changeType";
    public static final String EXTRA_FILE_NAME = "fileName";
    public static final String EXTRA_MODIFIED = "modified";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "FCM message received: " + remoteMessage.getData());

        if (remoteMessage.getData().size() > 0) {
            String changeType = remoteMessage.getData().get("changeType");
            String fileName = remoteMessage.getData().get("fileName");
            String modifiedStr = remoteMessage.getData().get("modified");

            if (changeType != null && fileName != null) {
                // If app is in foreground, handle immediately
                handleDriveChange(changeType, fileName, modifiedStr);

                // Show notification for user awareness and to trigger sync if tapped
                showNotification("Drive " + changeType, fileName + " changed. Tap to sync.", changeType, fileName, modifiedStr);
            }
        }
    }

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "FCM token refreshed: " + token);
        // TODO: Send token to your server if needed
    }

    private void handleDriveChange(String changeType, String fileName, String modifiedStr) {
        Intent intent = new Intent(ACTION_SYNC);
        intent.putExtra(EXTRA_CHANGE_TYPE, changeType);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        if (modifiedStr != null) {
            intent.putExtra(EXTRA_MODIFIED, modifiedStr);
        }
        intent.setPackage(getPackageName()); // Make broadcast explicit
        sendBroadcast(intent);
    }

    private void showNotification(String title, String message, String changeType, String fileName, String modifiedStr) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Drive Sync Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_CHANGE_TYPE, changeType);
        intent.putExtra(EXTRA_FILE_NAME, fileName);
        if (modifiedStr != null) {
            intent.putExtra(EXTRA_MODIFIED, modifiedStr);
        }
        intent.putExtra("trigger_sync", true);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        int notificationId = (int) System.currentTimeMillis();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setGroup(GROUP_KEY_DRIVE_SYNC);

        manager.notify(notificationId, builder.build());

        NotificationCompat.Builder summaryBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("DriveSync")
                .setContentText("Drive updates")
                .setStyle(new NotificationCompat.InboxStyle()
                        .addLine(title + ": " + message)
                        .setSummaryText("Drive folder updates"))
                .setGroup(GROUP_KEY_DRIVE_SYNC)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        manager.notify(0, summaryBuilder.build());
    }
}
package com.whynotyou.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Fires when the alarm goes off. Posts a full-screen-intent notification, which Android
 * turns into the AlarmActivity on a locked / screen-off phone.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Hold the CPU awake briefly so the notification + activity launch isn't dropped.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whynotyou:fire")
        wl.acquire(20_000L)

        AlarmScheduler.onFired(context)
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: AlarmScheduler.TEST_ID
        showFullScreen(context, alarmId)
    }

    private fun showFullScreen(context: Context, alarmId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Alarm", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Wake-up alarm"
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null) // the activity plays the video audio itself
        }
        nm.createNotificationChannel(channel)

        val fullScreen = PendingIntent.getActivity(
            context, 0,
            Intent(context, AlarmActivity::class.java)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm")
            .setContentText("Tap to open")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .build()

        nm.notify(NOTIFICATION_ID, n)

        // Belt and braces: try to launch directly too. Allowed on some OEMs / when app is foreground.
        try {
            context.startActivity(
                Intent(context, AlarmActivity::class.java)
                    .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }

    companion object {
        const val CHANNEL_ID = "alarm_channel"
        const val NOTIFICATION_ID = 42
    }
}

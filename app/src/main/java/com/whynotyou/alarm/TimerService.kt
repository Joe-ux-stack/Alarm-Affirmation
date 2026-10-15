package com.whynotyou.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Countdown as a foreground service so it survives the app being backgrounded. When it ends,
 * an exact alarm fires TimerEndReceiver which opens AlarmActivity in timer mode.
 */
class TimerService : Service() {

    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val endAt = intent.getLongExtra(EXTRA_END_AT, 0L)
                if (!TimerState.isActive(this)) TimerState.start(this, endAt)
                armEnd(endAt)
                if (android.os.Build.VERSION.SDK_INT >= 34)
                    startForeground(NOTIF_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIF_ID, notification())
                ui.removeCallbacks(tick); ui.post(tick)
            }
            ACTION_PAUSE -> { TimerState.pause(this); disarmEnd(); nm().notify(NOTIF_ID, notification()) }
            ACTION_RESUME -> { val endAt = TimerState.resume(this); armEnd(endAt); nm().notify(NOTIF_ID, notification()) }
            ACTION_CANCEL -> { TimerState.clear(this); disarmEnd(); stopSelf() }
        }
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!TimerState.isActive(this@TimerService)) { stopSelf(); return }
            nm().notify(NOTIF_ID, notification())
            ui.postDelayed(this, 1000)
        }
    }

    private fun notification(): Notification {
        nm().createNotificationChannel(NotificationChannel(CHANNEL, "Timer", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val remaining = TimerState.remainingMs(this) / 1000
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (TimerState.isPaused(this)) "Timer paused" else "Timer")
            .setContentText("%d:%02d".format(remaining / 60, remaining % 60))
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .build()
    }

    private fun armEnd(endAt: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(endAt, null), endPendingIntent())
    }
    private fun disarmEnd() { (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(endPendingIntent()) }
    private fun endPendingIntent() = PendingIntent.getBroadcast(this, 2002, Intent(this, TimerEndReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        const val ACTION_START = "start"; const val ACTION_PAUSE = "pause"; const val ACTION_RESUME = "resume"; const val ACTION_CANCEL = "cancel"
        const val EXTRA_END_AT = "end_at"
        const val CHANNEL = "timer"; const val NOTIF_ID = 77

        fun start(c: Context, durationMs: Long) {
            val endAt = System.currentTimeMillis() + durationMs
            TimerState.start(c, endAt)   // UI can switch immediately; the service re-reads this
            c.startForegroundService(Intent(c, TimerService::class.java).setAction(ACTION_START).putExtra(EXTRA_END_AT, endAt))
        }
        fun pause(c: Context) = c.startService(Intent(c, TimerService::class.java).setAction(ACTION_PAUSE))
        fun resume(c: Context) = c.startService(Intent(c, TimerService::class.java).setAction(ACTION_RESUME))
        fun cancel(c: Context) = c.startService(Intent(c, TimerService::class.java).setAction(ACTION_CANCEL))
    }
}

class TimerEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerState.clear(context)
        context.stopService(Intent(context, TimerService::class.java))
        context.startActivity(
            Intent(context, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_TIMER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }
}

/** Timer state in prefs so the UI and the service agree. */
object TimerState {
    private const val P = "timer"
    private fun prefs(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun start(c: Context, endAt: Long) = prefs(c).edit().putLong("end_at", endAt).putLong("paused_remaining", -1L).putLong("last_duration", endAt - System.currentTimeMillis()).apply()
    fun pause(c: Context) = prefs(c).edit().putLong("paused_remaining", remainingMs(c)).apply()
    fun resume(c: Context): Long {
        val endAt = System.currentTimeMillis() + prefs(c).getLong("paused_remaining", 0L)
        prefs(c).edit().putLong("end_at", endAt).putLong("paused_remaining", -1L).apply()
        return endAt
    }
    fun clear(c: Context) = prefs(c).edit().remove("end_at").remove("paused_remaining").apply()
    fun isActive(c: Context) = prefs(c).contains("end_at")
    fun isPaused(c: Context) = prefs(c).getLong("paused_remaining", -1L) >= 0
    fun remainingMs(c: Context): Long {
        val paused = prefs(c).getLong("paused_remaining", -1L)
        if (paused >= 0) return paused
        return (prefs(c).getLong("end_at", 0L) - System.currentTimeMillis()).coerceAtLeast(0)
    }
    fun lastDuration(c: Context) = prefs(c).getLong("last_duration", 10 * 60_000L)
}

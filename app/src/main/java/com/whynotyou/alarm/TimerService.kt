package com.whynotyou.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Countdown without a background service: the end is an AlarmClock alarm (fires even in Doze), and the
 * ongoing notification counts down by itself (chronometer), so nothing has to keep running.
 */
object TimerService {
    const val CHANNEL = "timer"; const val NOTIF_ID = 77

    fun start(c: Context, durationMs: Long) {
        val endAt = System.currentTimeMillis() + durationMs
        TimerState.start(c, endAt)
        armEnd(c, endAt); notifyState(c)
    }
    fun pause(c: Context) { TimerState.pause(c); disarmEnd(c); notifyState(c) }
    fun resume(c: Context) { val endAt = TimerState.resume(c); armEnd(c, endAt); notifyState(c) }
    fun cancel(c: Context) { TimerState.clear(c); disarmEnd(c); nm(c).cancel(NOTIF_ID) }

    private fun notifyState(c: Context) {
        nm(c).createNotificationChannel(NotificationChannel(CHANNEL, "Timer", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(c, 3, Intent(c, TimerStopReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val paused = TimerState.isPaused(c)
        val remaining = TimerState.remainingMs(c)
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (paused) "Timer paused" else "Timer")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "Stop", stop)
        if (paused) b.setContentText("%d:%02d left".format(remaining / 60000, remaining / 1000 % 60)).setShowWhen(false)
        else b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(System.currentTimeMillis() + remaining).setShowWhen(true)
        try { nm(c).notify(NOTIF_ID, b.build()) } catch (_: SecurityException) {}
    }

    private fun armEnd(c: Context, endAt: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(endAt, null), endPendingIntent(c))
    }
    private fun disarmEnd(c: Context) { (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(endPendingIntent(c)) }
    private fun endPendingIntent(c: Context) = PendingIntent.getBroadcast(c, 2002, Intent(c, TimerEndReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun nm(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}

class TimerStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = TimerService.cancel(context)
}

class TimerEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerState.clear(context)
        TimerService.nm(context).cancel(TimerService.NOTIF_ID)
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

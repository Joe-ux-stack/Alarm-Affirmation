package com.whynotyou.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Keeps exactly one pending system alarm: the soonest of all enabled alarms (or an active snooze /
 * test). After it fires and is dismissed, the next one is lined up. Survives reboots via BootReceiver.
 */
object AlarmScheduler {

    private const val PREFS = "scheduler"
    private const val KEY_PENDING_ID = "pending_id"
    private const val KEY_PENDING_AT = "pending_at"
    private const val KEY_SNOOZES = "snoozes_used"
    private const val REQUEST_CODE = 1001
    const val MAX_SNOOZES = 2
    const val EXTRA_ALARM_ID = "alarm_id"
    const val TEST_ID = "test"

    /** Recompute and arm the soonest alarm. Call after any alarm change. */
    fun reschedule(c: Context) {
        val next = AlarmStore.next(c)
        if (next == null) { cancel(c); return }
        arm(c, next.first.id, next.second)
    }

    fun scheduleTestInMinutes(c: Context, minutes: Int) {
        prefs(c).edit().putInt(KEY_SNOOZES, 0).apply()
        arm(c, TEST_ID, System.currentTimeMillis() + minutes * 60_000L)
    }

    fun snooze(c: Context, alarmId: String, minutes: Int = 5) {
        val used = prefs(c).getInt(KEY_SNOOZES, 0)
        if (used >= MAX_SNOOZES) return
        prefs(c).edit().putInt(KEY_SNOOZES, used + 1).apply()
        arm(c, alarmId, System.currentTimeMillis() + minutes * 60_000L)
    }

    fun snoozesLeft(c: Context) = (MAX_SNOOZES - prefs(c).getInt(KEY_SNOOZES, 0)).coerceAtLeast(0)

    /** Real dismissal: one-off alarms switch off; snoozes reset; next alarm armed. */
    fun onDismissed(c: Context, alarmId: String) {
        prefs(c).edit().putInt(KEY_SNOOZES, 0).apply()
        AlarmStore.get(c, alarmId)?.let { if (it.isOnce) { it.enabled = false; AlarmStore.save(c, it); return } }
        reschedule(c)
        BriefAudio.scheduleAll(c)
    }

    fun onFired(c: Context) { prefs(c).edit().remove(KEY_PENDING_AT).apply() }

    fun restore(c: Context) {
        val at = prefs(c).getLong(KEY_PENDING_AT, -1L)
        val id = prefs(c).getString(KEY_PENDING_ID, null)
        if (at > System.currentTimeMillis() && id != null) arm(c, id, at) else reschedule(c)
    }

    fun pendingAt(c: Context): Long? = prefs(c).getLong(KEY_PENDING_AT, -1L).takeIf { it > 0 }

    fun canScheduleExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    private fun arm(c: Context, alarmId: String, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), firePendingIntent(c, alarmId))
        prefs(c).edit().putString(KEY_PENDING_ID, alarmId).putLong(KEY_PENDING_AT, at).apply()
    }

    private fun cancel(c: Context) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(firePendingIntent(c, ""))
        prefs(c).edit().remove(KEY_PENDING_AT).remove(KEY_PENDING_ID).apply()
    }

    private fun firePendingIntent(c: Context, alarmId: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, REQUEST_CODE, Intent(c, AlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

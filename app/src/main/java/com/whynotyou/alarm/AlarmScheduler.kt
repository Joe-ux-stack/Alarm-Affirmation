package com.whynotyou.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * One alarm: a time of day, repeating every day it's enabled. Snooze up to 2× 5 min.
 * Survives reboots via stored prefs + BootReceiver.
 */
object AlarmScheduler {

    private const val PREFS = "alarm"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_NEXT = "next_time_millis"
    private const val KEY_SNOOZES = "snoozes_used"
    private const val REQUEST_CODE = 1001
    const val MAX_SNOOZES = 2

    fun setDaily(context: Context, hour: Int, minute: Int) {
        prefs(context).edit().putBoolean(KEY_ENABLED, true).putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).putInt(KEY_SNOOZES, 0).apply()
        scheduleNextOccurrence(context)
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).remove(KEY_NEXT).apply()
        cancelPending(context)
    }

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun hour(context: Context) = prefs(context).getInt(KEY_HOUR, 7)
    fun minute(context: Context) = prefs(context).getInt(KEY_MINUTE, 0)
    fun nextTime(context: Context): Long? = prefs(context).getLong(KEY_NEXT, -1L).takeIf { it > 0 }
    fun snoozesLeft(context: Context) = (MAX_SNOOZES - prefs(context).getInt(KEY_SNOOZES, 0)).coerceAtLeast(0)

    /** One-off test alarm in N minutes; doesn't change the daily setting. */
    fun scheduleInMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_SNOOZES, 0).apply()
        setExact(context, System.currentTimeMillis() + minutes * 60_000L)
    }

    fun snooze(context: Context, minutes: Int) {
        val used = prefs(context).getInt(KEY_SNOOZES, 0)
        if (used >= MAX_SNOOZES) return
        prefs(context).edit().putInt(KEY_SNOOZES, used + 1).apply()
        setExact(context, System.currentTimeMillis() + minutes * 60_000L)
    }

    /** After a real dismissal: reset snoozes and line up tomorrow. */
    fun onDismissed(context: Context) {
        prefs(context).edit().putInt(KEY_SNOOZES, 0).apply()
        if (isEnabled(context)) scheduleNextOccurrence(context) else prefs(context).edit().remove(KEY_NEXT).apply()
    }

    /** Called from the receiver when it fires: nothing to re-arm here; dismissal re-arms. */
    fun onFired(context: Context) { prefs(context).edit().remove(KEY_NEXT).apply() }

    /** After reboot / app update. */
    fun restore(context: Context) {
        val t = nextTime(context)
        when {
            t != null && t > System.currentTimeMillis() -> setExact(context, t)
            isEnabled(context) -> scheduleNextOccurrence(context)
        }
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    private fun scheduleNextOccurrence(context: Context) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(context)); set(Calendar.MINUTE, minute(context))
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 60_000L) add(Calendar.DAY_OF_YEAR, 1)
        }
        setExact(context, cal.timeInMillis)
    }

    private fun setExact(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), firePendingIntent(context))
        prefs(context).edit().putLong(KEY_NEXT, triggerAtMillis).apply()
    }

    private fun cancelPending(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(firePendingIntent(context))
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

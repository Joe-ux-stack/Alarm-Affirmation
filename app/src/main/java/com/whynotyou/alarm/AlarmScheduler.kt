package com.whynotyou.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** Schedules one alarm. The time is stored so it survives reboots. */
object AlarmScheduler {

    private const val PREFS = "alarm"
    private const val KEY_TIME = "next_time_millis"
    private const val REQUEST_CODE = 1001

    fun scheduleAt(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val fire = firePendingIntent(context)
        val show = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // setAlarmClock: exact, survives Doze, shows the alarm icon in the status bar.
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, show), fire)
        prefs(context).edit().putLong(KEY_TIME, triggerAtMillis).apply()
    }

    fun scheduleInMinutes(context: Context, minutes: Int) {
        scheduleAt(context, System.currentTimeMillis() + minutes * 60_000L)
    }

    fun scheduleAtTimeOfDay(context: Context, hour: Int, minute: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        scheduleAt(context, cal.timeInMillis)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePendingIntent(context))
        prefs(context).edit().remove(KEY_TIME).apply()
    }

    fun nextTime(context: Context): Long? =
        prefs(context).getLong(KEY_TIME, -1L).takeIf { it > 0 }

    /** Called after reboot: re-register if the stored time is still in the future. */
    fun restore(context: Context) {
        val t = nextTime(context) ?: return
        if (t > System.currentTimeMillis()) scheduleAt(context, t) else cancel(context)
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

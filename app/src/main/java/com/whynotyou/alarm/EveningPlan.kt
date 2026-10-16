package com.whynotyou.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * Evening plan: at a chosen time (default 9 pm), on evenings before a day with an alarm, a screen
 * pops up to plan tomorrow. Start / Later (15–60 min) / Skip tonight.
 */
object EveningPlan {
    private fun prefs(c: Context) = c.getSharedPreferences("plan", Context.MODE_PRIVATE)
    fun isOn(c: Context) = prefs(c).getBoolean("on", true)
    fun hour(c: Context) = prefs(c).getInt("h", 21)
    fun minute(c: Context) = prefs(c).getInt("m", 0)
    fun sound(c: Context) = prefs(c).getBoolean("sound", false)
    fun set(c: Context, on: Boolean = isOn(c), h: Int = hour(c), m: Int = minute(c), sound: Boolean = sound(c)) {
        prefs(c).edit().putBoolean("on", on).putInt("h", h).putInt("m", m).putBoolean("sound", sound).apply()
        schedule(c)
    }

    /** The next planning reminder: for each alarm with it on, the evening before its next ring, at its own time. */
    fun next(c: Context, from: Long = System.currentTimeMillis()): Long? {
        var best: Long? = null
        for (a in AlarmStore.all(c).filter { it.enabled && it.planRemind }) {
            var t = a.nextTrigger(from) ?: continue
            for (k in 0 until 8) {
                val eve = Calendar.getInstance().apply {
                    timeInMillis = t; add(Calendar.DAY_OF_YEAR, -1)
                    set(Calendar.HOUR_OF_DAY, a.planHour); set(Calendar.MINUTE, a.planMinute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (eve > from) { if (best == null || eve < best!!) best = eve; break }
                t = a.nextTrigger(t) ?: break
            }
        }
        return best
    }

    /** First alarm tomorrow (relative to now), for the prompt's subtitle. */
    fun tomorrowAlarm(c: Context): Alarm? {
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        return AlarmStore.all(c).filter { it.enabled }.mapNotNull { a -> a.nextTrigger()?.let { a to it } }
            .filter { (_, t) -> Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR) }
            .minByOrNull { it.second }?.first
    }

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 2003, Intent(c, PlanReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(c))
        if (!isOn(c)) return
        val at = next(c) ?: return
        arm(c, at)
    }

    fun later(c: Context, minutes: Int) = arm(c, System.currentTimeMillis() + minutes * 60_000L)

    private fun arm(c: Context, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c))
        } catch (_: SecurityException) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(c)) }
    }

    const val CHANNEL = "plan"; const val CHANNEL_SOUND = "plan_sound"; const val NOTIF_ID = 43
}

class PlanReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (!EveningPlan.isOn(c)) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val withSound = EveningPlan.sound(c)
        val chId = if (withSound) EveningPlan.CHANNEL_SOUND else EveningPlan.CHANNEL
        nm.createNotificationChannel(NotificationChannel(chId, if (withSound) "Evening plan (sound)" else "Evening plan", NotificationManager.IMPORTANCE_HIGH).apply {
            if (withSound) setSound(Uri.parse("android.resource://${c.packageName}/${R.raw.chime}"),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            else setSound(null, null)
        })
        val open = Intent(c, PlanPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, 4, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(EveningPlan.NOTIF_ID, NotificationCompat.Builder(c, chId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Plan tomorrow").setContentText("One line for the morning you.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(pi).setFullScreenIntent(pi, true).build())
        try { c.startActivity(open) } catch (_: Exception) {}
        EveningPlan.schedule(c)   // next evening
    }
}

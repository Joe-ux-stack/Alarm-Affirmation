package com.whynotyou.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One alarm. days[0]=Mon … days[6]=Sun. */
data class Alarm(
    val id: String,
    var hour: Int,
    var minute: Int,
    var days: BooleanArray = BooleanArray(7) { it < 5 },
    var enabled: Boolean = true,
    var volume: Int = 80,            // 0–100 of max alarm volume
    var ramp: Boolean = true,        // quiet start, full after 10 s
    var vibrate: Boolean = true,     // pulse while ringing
    var snooze: Boolean = true,      // 5 min, max 2
    var message: Boolean = true,     // play a loved one's message after Stop
    var brief: Boolean = true,       // read calendar + intention after the message
    var voice: String = VoiceEngine.DEFAULT_VOICE,
    var soundName: String = ""       // "" = built-in chime; else a song copied into filesDir/sounds
) {
    fun soundFile(c: android.content.Context) = java.io.File(c.filesDir, "sounds/$id.audio")
    fun hasCustomSound(c: android.content.Context) = soundName.isNotEmpty() && soundFile(c).exists()

    val isDaily get() = days.all { it }
    val isWeekdays get() = (0..4).all { days[it] } && !days[5] && !days[6]
    val isOnce get() = days.none { it }

    /** Next time this alarm fires, or null if disabled. */
    fun nextTrigger(now: Long = System.currentTimeMillis()): Long? {
        if (!enabled) return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now + 60_000L) cal.add(Calendar.DAY_OF_YEAR, 1)
        if (isOnce) return cal.timeInMillis
        for (i in 0 until 7) {
            val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0 … Sun=6
            if (days[dow]) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("h", hour).put("m", minute)
        .put("days", JSONArray().also { a -> days.forEach { a.put(it) } })
        .put("on", enabled).put("vol", volume).put("ramp", ramp).put("vib", vibrate).put("snooze", snooze)
        .put("msg", message).put("brief", brief).put("voice", voice).put("sound", soundName)

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val d = o.optJSONArray("days")
            return Alarm(
                id = o.getString("id"), hour = o.getInt("h"), minute = o.getInt("m"),
                days = BooleanArray(7) { i -> d?.optBoolean(i, i < 5) ?: (i < 5) },
                enabled = o.optBoolean("on", true), volume = o.optInt("vol", 80),
                ramp = o.optBoolean("ramp", true), vibrate = o.optBoolean("vib", true), snooze = o.optBoolean("snooze", true),
                message = o.optBoolean("msg", true), brief = o.optBoolean("brief", true),
                voice = o.optString("voice", VoiceEngine.DEFAULT_VOICE),
                soundName = o.optString("sound", "")
            )
        }
        fun new(hour: Int = 7, minute: Int = 0) = Alarm(System.currentTimeMillis().toString(36), hour, minute)
    }
}

object AlarmStore {
    private const val PREFS = "alarms"
    private const val KEY = "list"

    fun all(c: Context): List<Alarm> {
        val arr = JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        return (0 until arr.length()).map { Alarm.fromJson(arr.getJSONObject(it)) }
            .sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun get(c: Context, id: String): Alarm? = all(c).firstOrNull { it.id == id }

    fun save(c: Context, alarm: Alarm) {
        val list = all(c).filter { it.id != alarm.id } + alarm
        write(c, list)
        AlarmScheduler.reschedule(c)
        BriefAudio.scheduleAll(c)
        EveningPlan.schedule(c)
    }

    fun delete(c: Context, id: String) {
        java.io.File(c.filesDir, "sounds/$id.audio").delete()
        write(c, all(c).filter { it.id != id })
        AlarmScheduler.reschedule(c)
        BriefAudio.scheduleAll(c)
        EveningPlan.schedule(c)
    }

    fun setEnabled(c: Context, id: String, on: Boolean) {
        get(c, id)?.let { it.enabled = on; save(c, it) }
    }

    /** The alarm that fires soonest, with its trigger time. */
    fun next(c: Context): Pair<Alarm, Long>? =
        all(c).mapNotNull { a -> a.nextTrigger()?.let { a to it } }.minByOrNull { it.second }

    private fun write(c: Context, list: List<Alarm>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

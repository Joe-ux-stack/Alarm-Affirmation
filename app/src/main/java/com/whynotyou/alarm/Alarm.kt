package com.whynotyou.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** One alarm. days[0]=Mon … days[6]=Sun. */
data class Alarm(
    var id: String,
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
    var soundName: String = "",      // "" = built-in chime; else a song copied into filesDir/sounds
    var vibePattern: String = "pulse", // pulse | heartbeat | steady | gentle
    var planRemind: Boolean = true,  // remind the night before to write the plan
    var planHour: Int = 21,
    var planMinute: Int = 0,
    var label: String = "",          // e.g. "Work"; shown on the card and when it rings
    var note: String = "",           // read aloud when this alarm rings (instead of the evening plan)
    var noteFromAi: Boolean = false,
    var date: String = "",           // one-time alarm on a specific day, "yyyy-MM-dd"; "" = next time it comes up
    var followup: String = "",       // yes/no follow-up tree as JSON (see Followup.kt); "" = none
    var followupRepeat: Int = 0,     // how many times this follow-up has already repeated
    var followupDepth: Int = 0,      // 0 = set by the AI; 1..3 = created by a follow-up branch
    var auto: Boolean = false        // made by a follow-up; removed after it rings
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
        if (isOnce && date.isNotEmpty()) {
            val d = try { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(date) } catch (_: Throwable) { null }
            if (d != null) {
                val dc = Calendar.getInstance().apply { time = d }
                cal.timeInMillis = now
                cal.set(dc.get(Calendar.YEAR), dc.get(Calendar.MONTH), dc.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return if (cal.timeInMillis > now) cal.timeInMillis else null
            }
        }
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
        .put("vibp", vibePattern).put("pr", planRemind).put("ph", planHour).put("pm", planMinute).put("lbl", label).put("note", note).put("noteAi", noteFromAi).put("date", date)
        .put("fu", followup).put("fuN", followupRepeat).put("fuD", followupDepth).put("auto", auto)

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
                soundName = o.optString("sound", ""),
                vibePattern = o.optString("vibp", "pulse"),
                planRemind = o.optBoolean("pr", true), planHour = o.optInt("ph", 21), planMinute = o.optInt("pm", 0),
                label = o.optString("lbl", ""), note = o.optString("note", ""),
                noteFromAi = o.optBoolean("noteAi", false), date = o.optString("date", ""),
                followup = o.optString("fu", ""), followupRepeat = o.optInt("fuN", 0), followupDepth = o.optInt("fuD", 0), auto = o.optBoolean("auto", false)
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
            // Soonest first (so a week planned on Sunday reads in order); switched-off alarms after, by time.
            .sortedWith(compareBy<Alarm>({ it.nextTrigger() ?: Long.MAX_VALUE }, { it.hour }, { it.minute }))
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

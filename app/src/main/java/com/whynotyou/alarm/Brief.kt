package com.whynotyou.alarm

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One event for the morning brief. Calendar events and manual items both use this. */
data class BriefItem(val startMillis: Long, val title: String, val allDay: Boolean = false)

/**
 * Builds the spoken morning brief from cached calendar events plus the user's manual intention.
 * Calendar events are written in by a sync the night before; nothing here touches the network.
 */
object Brief {

    private const val PREFS = "brief"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTENTION = "intention"
    private const val KEY_SCOPE = "scope"          // "three" | "lunch" | "all"
    private const val KEY_EVENTS = "events"        // "start|title\nstart|title"
    private const val KEY_EVENTS_DAY = "events_day" // yyyyMMdd the events were fetched for

    fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun intention(c: Context): String = prefs(c).getString(KEY_INTENTION, "") ?: ""
    fun setIntention(c: Context, s: String) {
        val old = intention(c)
        prefs(c).edit().putString(KEY_INTENTION, s.trim()).apply()
        if (old != s.trim()) BriefAudio.refreshNow(c)
    }
    fun clearIntention(c: Context) = setIntention(c, "")

    // Plans for a specific date ("2026-10-22" -> text), read by the first alarm that day without its own message.
    fun datePlans(c: Context): Map<String, String> = try {
        val o = org.json.JSONObject(prefs(c).getString("date_plans", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Throwable) { emptyMap() }
    fun datePlan(c: Context, at: Long): String = datePlans(c)[isoDay(at)].orEmpty()
    fun setDatePlan(c: Context, date: String, plan: String) {
        val today = isoDay(System.currentTimeMillis())
        val m = datePlans(c).filterKeys { it >= today }.toMutableMap()   // drop days that have passed
        if (plan.isBlank()) m.remove(date) else m[date] = plan.trim()
        prefs(c).edit().putString("date_plans", org.json.JSONObject(m as Map<*, *>).toString()).apply()
        BriefAudio.refreshNow(c)
    }
    fun isoDay(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    fun scope(c: Context): String = prefs(c).getString(KEY_SCOPE, "lunch") ?: "lunch"
    fun setScope(c: Context, s: String) = prefs(c).edit().putString(KEY_SCOPE, s).apply()

    /** Called by the calendar sync. Replaces today's cached events. */
    fun storeEvents(c: Context, items: List<BriefItem>, forDay: Date = Date()) {
        val body = items.sortedBy { it.startMillis }
            .joinToString("\n") { "${it.startMillis}|${if (it.allDay) 1 else 0}|${it.title.replace("\n", " ").replace("|", "/")}" }
        prefs(c).edit()
            .putString(KEY_EVENTS, body)
            .putString(KEY_EVENTS_DAY, dayKey(forDay))
            .apply()
    }

    fun cachedEvents(c: Context): List<BriefItem> {
        if (prefs(c).getString(KEY_EVENTS_DAY, "") != dayKey(Date())) return emptyList()
        return (prefs(c).getString(KEY_EVENTS, "") ?: "")
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size < 3) null
                else parts[0].toLongOrNull()?.let { BriefItem(it, parts[2], parts[1] == "1") }
            }
            .toList()
    }

    /** The text that gets spoken. Empty string when there's nothing to say. */
    fun compose(c: Context, now: Date = Date(), alarm: Alarm? = null): String {
        if (!isEnabled(c)) return ""
        val parts = mutableListOf<String>()

        val hour = Calendar.getInstance().apply { time = now }.get(Calendar.HOUR_OF_DAY)
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(now)
        parts += when {
            hour < 12 -> "Good morning. It's $day."
            hour < 18 -> "Good afternoon."
            else -> "Good evening."
        }

        val cached = cachedEvents(c)
        val allDayItems = cached.filter { it.allDay }
        val all = cached.filter { !it.allDay && it.startMillis >= now.time - 5 * 60_000L }
        val chosen = when (scope(c)) {
            "three" -> all.take(3)
            "all" -> all
            else -> {
                val lunch = Calendar.getInstance().apply {
                    time = now; set(Calendar.HOUR_OF_DAY, 13); set(Calendar.MINUTE, 0)
                }.timeInMillis
                val untilLunch = all.filter { it.startMillis <= lunch }
                if (untilLunch.isEmpty()) all.take(1) else untilLunch
            }
        }
        val rest = all.size - chosen.size

        if (allDayItems.isNotEmpty()) {
            parts += "Today: " + allDayItems.joinToString(", ") { it.title } + "."
        }
        if (all.isEmpty()) {
            if (allDayItems.isEmpty()) parts += "Nothing on the calendar."
        } else {
            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            chosen.forEach { parts += "${it.title}, at ${fmt.format(Date(it.startMillis)).replace(":00", "")}." }
            if (rest > 0) parts += "And $rest more ${if (rest == 1) "thing" else "things"} later."
        }

        // This alarm's own message wins (said as written); otherwise the evening plan.
        val msg = alarm?.note?.trim().orEmpty().ifEmpty { datePlan(c, now.time).trim() }
        if (msg.isNotEmpty()) parts += if (msg.last() in ".!?") msg else "$msg."
        else intention(c).takeIf { it.isNotBlank() }?.let { parts += Phrase.intention(it, now.time) }
        // A follow-up question is asked right after the message; the answer buttons appear when it's said.
        alarm?.let { Followup.question(it) }?.takeIf { it.isNotBlank() }?.let { parts += if (it.last() in ".!?") it else "$it?" }
        return parts.joinToString(" ")
    }

    private fun dayKey(d: Date) = SimpleDateFormat("yyyyMMdd", Locale.US).format(d)
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

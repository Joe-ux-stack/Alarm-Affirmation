package com.whynotyou.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Follow-up alarms: a small yes/no decision tree the AI attaches to an alarm. Runs entirely on the
 * phone when the alarm rings (no AI call):
 *
 *   { "question": "Are they crisp?",
 *     "yes": { "say": "Take them out.", "then": "done" },
 *     "no":  { "say": "I'll check again in an hour.",
 *              "then": { "in_minutes": 60, "repeat_this_alarm": true, "max_repeats": 3 } } }
 *
 * `then` is "done", a repeat of this alarm, or { "in_minutes": N, "alarm": { label, message, followup } }.
 */
object Followup {
    const val MAX_DEPTH = 3
    const val ANSWER_TIMEOUT_MS = 3 * 60_000L

    fun tree(a: Alarm): JSONObject? = try { if (a.followup.isBlank()) null else JSONObject(a.followup) } catch (_: Throwable) { null }
    fun question(a: Alarm): String = tree(a)?.optString("question").orEmpty().trim()
    fun say(a: Alarm, yes: Boolean): String = tree(a)?.optJSONObject(if (yes) "yes" else "no")?.optString("say").orEmpty().trim()

    /**
     * Runs the chosen branch's `then`: schedules the next alarm if there is one.
     * Returns a short line for the screen and the history ("Next check · 10:01 pm (2 of 3)"), or "" when done.
     */
    fun run(c: Context, a: Alarm, yes: Boolean): String {
        val branch = tree(a)?.optJSONObject(if (yes) "yes" else "no") ?: return ""
        val then = branch.opt("then")
        if (then !is JSONObject) return ""                       // "done" or missing
        val minutes = then.optInt("in_minutes", 0).coerceIn(1, 24 * 60)
        if (then.optBoolean("repeat_this_alarm", false)) {
            val max = then.optInt("max_repeats", 1).coerceIn(1, 10)
            val n = a.followupRepeat + 1
            if (n > max) return "That was the last check ($max of $max)"
            val next = spawn(c, a, minutes, a.label, a.note, a.followup, n, a.followupDepth)
            return "Next check · ${clock(c, next)} ($n of $max)"
        }
        val nextAlarm = then.optJSONObject("alarm") ?: return ""
        val depth = a.followupDepth + 1
        if (depth > MAX_DEPTH) return ""
        val next = spawn(c, a, minutes, nextAlarm.optString("label", a.label), nextAlarm.optString("message", ""),
            nextAlarm.optJSONObject("followup")?.toString() ?: "", 0, depth)
        return "Next alarm · ${clock(c, next)}" + (if (nextAlarm.optString("label").isNotBlank()) " · ${nextAlarm.optString("label")}" else "")
    }

    /** A one-time alarm N minutes from now that inherits this alarm's sound, voice and volume. */
    private fun spawn(c: Context, parent: Alarm, minutes: Int, label: String, note: String, followup: String, repeat: Int, depth: Int): Alarm {
        val at = Calendar.getInstance().apply { add(Calendar.MINUTE, minutes) }
        val a = Alarm.fromJson(parent.toJson()).apply {
            id = "fu" + System.currentTimeMillis().toString(36)
            hour = at.get(Calendar.HOUR_OF_DAY); minute = at.get(Calendar.MINUTE)
            days = BooleanArray(7); enabled = true
            date = Brief.isoDay(at.timeInMillis)
            this.label = label; this.note = note; noteFromAi = note.isNotBlank()
            this.followup = followup; followupRepeat = repeat; followupDepth = depth
            auto = true
        }
        AlarmStore.save(c, a)
        return a
    }

    private fun clock(c: Context, a: Alarm): String {
        val h = a.hour; val m = a.minute
        return if (android.text.format.DateFormat.is24HourFormat(c)) "%d:%02d".format(h, m)
        else "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "am" else "pm")
    }
}

/** Every follow-up answer with its time, so the AI can see what happened (get_alarm_history). */
object AlarmHistory {
    private fun p(c: Context) = c.getSharedPreferences("history", Context.MODE_PRIVATE)
    fun all(c: Context): JSONArray = try { JSONArray(p(c).getString("list", "[]")) } catch (_: Throwable) { JSONArray() }
    fun add(c: Context, a: Alarm, question: String, answer: String, result: String) {
        val list = all(c)
        list.put(JSONObject().put("at", System.currentTimeMillis()).put("alarm_id", a.id).put("label", a.label)
            .put("question", question).put("answer", answer).put("next", result.ifBlank { "done" }))
        val trimmed = JSONArray(); val from = (list.length() - 100).coerceAtLeast(0)
        for (i in from until list.length()) trimmed.put(list.get(i))
        p(c).edit().putString("list", trimmed.toString()).apply()
    }
}

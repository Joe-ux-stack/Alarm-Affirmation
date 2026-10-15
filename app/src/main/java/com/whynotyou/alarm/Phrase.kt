package com.whynotyou.alarm

import java.util.Calendar

/** Turns what you typed last night into a line worth waking up to. Rotates by day. */
object Phrase {
    private val templates = listOf(
        "Last night you decided: %s. Today's the day.",
        "Today you're going to %s.",
        "One thing matters today. %s.",
        "You told yourself you'd %s. Go make it true.",
        "Today, %s. You've got this.",
        "The plan is simple: %s.",
    )

    fun intention(raw: String, now: Long = System.currentTimeMillis()): String {
        var t = raw.trim().trimEnd('.', '!', ' ')
        // "I will finish…", "I'll finish…", "to finish…" -> "finish…"
        t = t.replace(Regex("^(i\\s+will|i'll|i\\s+want\\s+to|i\\s+need\\s+to|i\\s+have\\s+to|to)\\s+", RegexOption.IGNORE_CASE), "")
        if (t.isEmpty()) return ""
        t = t.replace(Regex("\\bmy\\b", RegexOption.IGNORE_CASE), "your").replace(Regex("\\bI\\b"), "you")
        val day = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR)
        val tpl = templates[day % templates.size]
        val body = if (tpl.startsWith("One thing") || tpl.startsWith("Today, ")) t.replaceFirstChar { it.uppercase() }.let { if (tpl.startsWith("Today, ")) t else it } else t.replaceFirstChar { it.lowercase() }
        return tpl.format(body)
    }
}

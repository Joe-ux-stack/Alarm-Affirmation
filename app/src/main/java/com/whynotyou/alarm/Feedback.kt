package com.whynotyou.alarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.LinearLayout
import android.widget.Toast

/** Tester feedback: an email with the app version and phone filled in, plus a one-time ask after a few days. */
object Feedback {
    private const val TO = "joerochow@gmail.com"
    private fun p(c: Context) = c.getSharedPreferences("feedback", Context.MODE_PRIVATE)

    fun send(a: Activity, about: String = "") {
        val body = (if (about.isNotBlank()) "$about\n\n" else "") +
            "What happened, or what would make it better?\n\n\n\n" +
            "---\nWhy Not You ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(TO))
            putExtra(Intent.EXTRA_SUBJECT, "Why Not You feedback")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try { a.startActivity(i) } catch (_: Exception) { Toast.makeText(a, "No email app found. Write to $TO", Toast.LENGTH_LONG).show() }
    }

    /** Once, three days after the first open: a short "how's it going?" sheet. */
    fun maybeAsk(a: Activity) {
        val first = p(a).getLong("first", 0L).let { if (it == 0L) System.currentTimeMillis().also { now -> p(a).edit().putLong("first", now).apply() } else it }
        if (p(a).getBoolean("asked", false) || System.currentTimeMillis() - first < 3 * 86_400_000L) return
        p(a).edit().putBoolean("asked", true).apply()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var d: com.google.android.material.bottomsheet.BottomSheetDialog? = null
        col.addView(Ui.button(a, "Send feedback", true, 56) { d?.dismiss(); send(a) })
        col.addView(Ui.button(a, "Not now", false, 52) { d?.dismiss() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = Ui.dp(a, 8)
        })
        d = Sheet.show(a, "How's it going?", "You've been waking up with Why Not You for a few days. What works, what doesn't? One line helps a lot.", col, done = null)
    }
}

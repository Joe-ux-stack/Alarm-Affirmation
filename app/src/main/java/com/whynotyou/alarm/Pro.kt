package com.whynotyou.alarm

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.LinearLayout

/**
 * Pro status. Until Google Play Billing is connected this is a local switch (Settings → Test →
 * "Pretend I have Pro") so both versions of the app can be tried on a phone.
 */
object Pro {
    private fun p(c: Context) = c.getSharedPreferences("pro", Context.MODE_PRIVATE)
    fun isPro(c: Context) = p(c).getBoolean("test_pro", false) || Account.serverPro(c)
    fun setTestPro(c: Context, on: Boolean) { p(c).edit().putBoolean("test_pro", on).apply() }

    /** The "with Pro" pop-up shown when a free user taps a Pro feature. */
    fun lock(a: Activity, title: String, body: String) {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(a, body, 15f, Ui.GREY).apply { setLineSpacing(0f, 1.3f) })
        var d: com.google.android.material.bottomsheet.BottomSheetDialog? = null
        col.addView(Ui.button(a, "Try Pro free for 7 days", true, 60) { d?.dismiss(); a.startActivity(Intent(a, TrialActivity::class.java)) }
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(a, 20) })
        col.addView(Ui.button(a, "Not now", false, 52) { d?.dismiss() }
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = Ui.dp(a, 8) })
        d = Sheet.show(a, title, null, col, done = null)
    }
}

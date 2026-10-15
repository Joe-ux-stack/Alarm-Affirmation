package com.whynotyou.alarm

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** The evening pop-up: orb, "Plan tomorrow", Start / Later / Skip tonight. */
class PlanPromptActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(EveningPlan.NOTIF_ID)

        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF000000.toInt()); setPadding(dp(16), dp(96), dp(16), dp(32))
        }
        root.addView(OrbView(this).apply { mode = OrbView.Mode.BREATHE }, LinearLayout.LayoutParams(dp(200), dp(200)))
        root.addView(TextView(this).apply {
            text = "Plan tomorrow"; textSize = 30f; setTextColor(0xFFF2F4F8.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(36) })
        val a = EveningPlan.tomorrowAlarm(this)
        root.addView(TextView(this).apply {
            text = if (a != null) "Alarm at ${"%d:%02d".format(if (a.hour % 12 == 0) 12 else a.hour % 12, a.minute)} ${if (a.hour < 12) "am" else "pm"}" else ""
            textSize = 15f; setTextColor(0xFF7A8294.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(android.view.View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val start = MaterialButton(this).apply {
            text = "Start"; isAllCaps = false; textSize = 18f; setTextColor(0xFF000000.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF2F4F8.toInt()); cornerRadius = dp(32)
            setOnClickListener { PlanSheet.show(this@PlanPromptActivity) { finish() } }
        }
        val later = MaterialButton(this).apply {
            text = "Later"; isAllCaps = false; textSize = 16f; setTextColor(0xFFB8BFCC.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF15181F.toInt()); cornerRadius = dp(28)
            setOnClickListener {
                val opts = intArrayOf(15, 30, 60)
                AlertDialog.Builder(this@PlanPromptActivity).setTitle("Remind me in")
                    .setItems(opts.map { "$it min" }.toTypedArray()) { _, i -> EveningPlan.later(this@PlanPromptActivity, opts[i]); finish() }.show()
            }
        }
        val skip = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Skip tonight"; isAllCaps = false; textSize = 15f; setTextColor(0xFF7A8294.toInt())
            setOnClickListener { finish() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(later, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        root.addView(skip, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(4) })
        setContentView(root)
    }
}

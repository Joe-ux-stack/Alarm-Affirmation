package com.whynotyou.alarm

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/** Settings → Evening plan: on/off, time, sound, plan now. */
class EveningPlanActivity : AppCompatActivity() {
    private lateinit var col: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(56), dp(12), dp(48)); setBackgroundColor(0xFF000000.toInt()) }
        setContentView(col)
    }
    override fun onResume() { super.onResume(); render() }

    private fun render() {
        col.removeAllViews()
        col.addView(TextView(this).apply { text = "Evening plan"; textSize = 28f; setTextColor(0xFFF2F4F8.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "On evenings before an alarm, a screen asks what tomorrow is for. Your answer is read to you in the morning."
            textSize = 14f; setTextColor(0xFF7A8294.toInt()); setPadding(0, dp(6), 0, dp(20))
        })
        row("On") { addView(SwitchCompat(this@EveningPlanActivity).apply {
            isChecked = EveningPlan.isOn(this@EveningPlanActivity)
            setOnCheckedChangeListener { _, v -> EveningPlan.set(this@EveningPlanActivity, on = v); render() }
        }) }
        if (EveningPlan.isOn(this)) {
            val h = EveningPlan.hour(this); val m = EveningPlan.minute(this)
            row("Time") {
                addView(value("%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "am" else "pm")))
                setOnClickListener {
                    TimePickerDialog(this@EveningPlanActivity, { _, hh, mm -> EveningPlan.set(this@EveningPlanActivity, h = hh, m = mm); render() }, h, m, false).show()
                }
            }
            row("With a sound") { addView(SwitchCompat(this@EveningPlanActivity).apply {
                isChecked = EveningPlan.sound(this@EveningPlanActivity)
                setOnCheckedChangeListener { _, v -> EveningPlan.set(this@EveningPlanActivity, sound = v) }
            }) }
            val next = EveningPlan.next(this)
            col.addView(TextView(this).apply {
                text = if (next == null) "No alarms set this week, so no plan screen." else
                    "Next: " + java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(java.util.Date(next))
                textSize = 13f; setTextColor(0xFF555B68.toInt()); setPadding(0, dp(12), 0, 0)
            })
        }
        row("Plan now") {
            addView(value("›"))
            setOnClickListener { startActivity(Intent(this@EveningPlanActivity, PlanPromptActivity::class.java)) }
        }
    }

    private fun row(label: String, build: LinearLayout.() -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56); setPadding(0, dp(12), 0, dp(12))
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        r.addView(TextView(this).apply {
            text = label; textSize = 16f; setTextColor(0xFFF2F4F8.toInt()); setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        r.build(); col.addView(r)
        col.addView(View(this).apply { setBackgroundColor(0xFF15181F.toInt()); layoutParams = LinearLayout.LayoutParams(-1, 1) })
    }
    private fun value(t: String) = TextView(this).apply { text = t; textSize = 15f; setTextColor(0xFF9AA3B5.toInt()) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

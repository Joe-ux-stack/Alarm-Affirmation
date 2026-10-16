package com.whynotyou.alarm

import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 3.3 Write the plan: one focused question, the text box, then tomorrow's calendar (Pro). */
class PlanActivity : ScreenActivity() {
    private var input: EditText? = null

    override fun build() {
        val keep = input?.text?.toString()
        header("")
        orb(110, -8)
        val alarm = EveningPlan.tomorrowAlarm(this)
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
        val sub = if (alarm != null) "$day · alarm " + MainActivity.formatTime(alarm.hour, alarm.minute).toString() else day
        add(Ui.text(this, sub, 14f, Ui.GREY).apply { gravity = Gravity.CENTER }, 24)
        add(Ui.text(this, "What's the plan\nfor tomorrow?", 28f, Ui.TEXT, true).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f) }, 8)

        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; maxLines = 8; gravity = Gravity.TOP or Gravity.START; textSize = 18f
            setTextColor(Ui.TEXT); setHintTextColor(0xFF6F7EA6.toInt()); hint = "e.g. Finish the pitch deck, then go for a run"
            setBackgroundResource(R.drawable.bg_box); setPadding(dp(18), dp(18), dp(18), dp(18)); minHeight = dp(120)
            setText(keep ?: Brief.intention(this@PlanActivity)); setSelection(text.length)
        }
        input = et
        add(et, 28)

        val cap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cap.addView(Ui.caption(this, "Calendar for tomorrow", 0).apply { setPadding(0, 0, 0, 0) })
        cap.addView(Ui.proBadge(this))
        add(cap, 24)

        val box = Ui.box(this, 16, 6)
        val pro = Pro.isPro(this)
        when {
            !pro -> box.addView(lockedRow("See tomorrow's events here") {
                Pro.lock(this, "Your calendar with Pro", "Tomorrow's events go into your wake-up plan and the evening reminder.")
            })
            !CalendarSync.hasPermission(this) -> box.addView(lockedRow("Allow calendar access") { startActivity(Intent(this, CalendarActivity::class.java)) })
            else -> {
                val events = CalendarSync.eventsFor(this, Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) })
                val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                if (events.isEmpty()) box.addView(Ui.text(this, "Nothing on the calendar", 15f, Ui.GREY).apply { setPadding(0, dp(12), 0, dp(12)) })
                events.forEachIndexed { i, e ->
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
                    r.addView(Ui.text(this, if (e.allDay) "All day" else fmt.format(Date(e.startMillis)).replace("AM", "am").replace("PM", "pm"), 14f, Ui.GREY), LinearLayout.LayoutParams(dp(78), -2))
                    r.addView(Ui.text(this, e.title, 15f), LinearLayout.LayoutParams(0, -2, 1f))
                    box.addView(r); if (i < events.size - 1) box.addView(Ui.divider(this))
                }
            }
        }
        add(box, 10)
        pinned(Ui.button(this, "Save plan", true, 64) { save(); finish() })
    }

    private fun lockedRow(t: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setOnClickListener { click() }
        addView(Ui.text(this@PlanActivity, t, 15f, Ui.GREY), LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.text(this@PlanActivity, "›", 18f, Ui.GREY).apply { typeface = Typeface.DEFAULT })
    }

    private fun save() {
        val t = input?.text?.toString()?.trim() ?: return
        Brief.setIntention(this, t)
        if (t.isNotEmpty()) Mornings.recordPlan(this)
    }

    override fun onPause() { super.onPause(); save() }
}

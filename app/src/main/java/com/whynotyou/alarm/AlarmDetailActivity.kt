package com.whynotyou.alarm

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AlarmDetailActivity : AppCompatActivity() {

    private lateinit var alarm: Alarm
    private var isNew = false

    private lateinit var time: TextView
    private lateinit var am: TextView
    private lateinit var pm: TextView
    private lateinit var daysRow: LinearLayout
    private val dayViews = arrayOfNulls<TextView>(7)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_detail)

        val id = intent.getStringExtra(EXTRA_ID)
        alarm = id?.let { AlarmStore.get(this, it) } ?: Alarm.new().also { isNew = true }
        if (isNew) {
            val now = Calendar.getInstance(); alarm.hour = now.get(Calendar.HOUR_OF_DAY); alarm.minute = now.get(Calendar.MINUTE)
        }

        time = findViewById(R.id.txt_time); am = findViewById(R.id.btn_am); pm = findViewById(R.id.btn_pm)
        daysRow = findViewById(R.id.row_days)

        time.setOnClickListener {
            TimePickerDialog(this, R.style.DialogDark, { _, h, m -> alarm.hour = h; alarm.minute = m; renderTime() }, alarm.hour, alarm.minute, false).show()
        }
        am.setOnClickListener { if (alarm.hour >= 12) alarm.hour -= 12; renderTime() }
        pm.setOnClickListener { if (alarm.hour < 12) alarm.hour += 12; renderTime() }

        val letters = "MTWTFSS"
        for (i in 0 until 7) {
            val tv = TextView(this).apply {
                text = letters[i].toString(); textSize = 13f; gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i < 6) marginEnd = dp(6) }
                setOnClickListener { alarm.days[i] = !alarm.days[i]; renderDays() }
            }
            dayViews[i] = tv; daysRow.addView(tv)
        }

        val seek = findViewById<SeekBar>(R.id.seek_volume); val volLabel = findViewById<TextView>(R.id.txt_volume)
        seek.progress = alarm.volume; volLabel.text = "${alarm.volume}%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { alarm.volume = p.coerceAtLeast(5); volLabel.text = "${alarm.volume}%" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        bind(R.id.sw_ramp, alarm.ramp) { alarm.ramp = it }
        bind(R.id.sw_snooze, alarm.snooze) { alarm.snooze = it }
        bind(R.id.sw_message, alarm.message) { alarm.message = it }
        bind(R.id.sw_brief, alarm.brief) { alarm.brief = it }

        findViewById<Button>(R.id.btn_delete).apply {
            visibility = if (isNew) View.GONE else View.VISIBLE
            setOnClickListener { AlarmStore.delete(this@AlarmDetailActivity, alarm.id); finish() }
        }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { alarm.enabled = true; AlarmStore.save(this, alarm); finish() }

        renderTime(); renderDays()
    }

    private fun bind(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        findViewById<SwitchCompat>(id).apply { isChecked = value; setOnCheckedChangeListener { _, on -> set(on) } }
    }

    private fun renderTime() {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, alarm.hour); set(Calendar.MINUTE, alarm.minute) }
        time.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(cal.time)
        val isAm = alarm.hour < 12
        style(am, isAm); style(pm, !isAm)
    }

    private fun style(tv: TextView, on: Boolean) {
        tv.setBackgroundResource(if (on) R.drawable.bg_pill_amber else R.drawable.bg_pill_outline)
        tv.setTextColor(if (on) 0xFF000000.toInt() else 0xFF7A8294.toInt())
    }

    private fun renderDays() {
        for (i in 0 until 7) {
            val tv = dayViews[i] ?: continue
            val on = alarm.days[i]
            tv.setBackgroundResource(if (on) R.drawable.bg_circle_amber else R.drawable.bg_circle_outline)
            tv.setTextColor(if (on) 0xFF000000.toInt() else 0xFF7A8294.toInt())
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_ID = "id" }
}

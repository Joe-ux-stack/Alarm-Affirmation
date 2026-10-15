package com.whynotyou.alarm

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.util.Calendar

class AlarmDetailActivity : AppCompatActivity() {

    private lateinit var alarm: Alarm
    private var isNew = false

    private lateinit var wh: WheelView
    private lateinit var wm: WheelView
    private lateinit var wap: WheelView
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

        wh = findViewById(R.id.wheel_h); wm = findViewById(R.id.wheel_m); wap = findViewById(R.id.wheel_ap)
        daysRow = findViewById(R.id.row_days)

        wh.setRange(1, 12); wm.setRange(0, 59); wap.setRange(0, 1); wap.labels = listOf("am", "pm")
        wh.onChange = { readWheels() }; wm.onChange = { readWheels() }; wap.onChange = { readWheels() }

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
        val voiceLabel = findViewById<TextView>(R.id.txt_voice)
        voiceLabel.text = VoiceEngine.voice(alarm.voice).name
        findViewById<View>(R.id.row_voice).setOnClickListener {
            VoicePicker.show(this, alarm.voice) { id -> alarm.voice = id; voiceLabel.text = VoiceEngine.voice(id).name }
        }

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
        val h12 = alarm.hour % 12; wh.value = if (h12 == 0) 12 else h12
        wm.value = alarm.minute
        wap.value = if (alarm.hour < 12) 0 else 1
    }

    private fun readWheels() {
        val h12 = wh.value % 12
        alarm.hour = h12 + if (wap.value == 1) 12 else 0
        alarm.minute = wm.value
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

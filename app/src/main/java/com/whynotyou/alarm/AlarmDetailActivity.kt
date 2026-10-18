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

        wh.setRange(1, 12); wm.setRange(0, 59); wap.setRange(0, 1); wap.labels = listOf("am", "pm"); wap.wrap = false
        wh.onChange = { readWheels() }; wm.onChange = { readWheels() }; wap.onChange = { readWheels() }

        val letters = "MTWTFSS"
        for (i in 0 until 7) {
            val tv = TextView(this).apply {
                text = letters[i].toString(); textSize = 13f; gravity = Gravity.CENTER
                contentDescription = listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")[i]
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (i < 6) marginEnd = dp(6) }
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
        bind(R.id.sw_vibrate, alarm.vibrate) { alarm.vibrate = it }
        bind(R.id.sw_snooze, alarm.snooze) { alarm.snooze = it }
        bind(R.id.sw_brief, alarm.brief) { alarm.brief = it }
        bind(R.id.sw_reminder, alarm.planRemind) { alarm.planRemind = it }
        renderTomorrow(); renderReminder(); renderVibe()
        findViewById<View>(R.id.row_tomorrow).setOnClickListener { startActivity(android.content.Intent(this, PlanActivity::class.java)) }
        findViewById<View>(R.id.row_reminder).setOnClickListener { reminderSheet() }
        renderLabel()
        findViewById<View>(R.id.row_label).setOnClickListener { labelSheet() }
        findViewById<View>(R.id.row_vibration).setOnClickListener {
            val ids = listOf("pulse", "heartbeat", "steady", "gentle")
            val opts = listOf("Pulse" to "Short buzz, pause, repeat", "Heartbeat" to "Two quick buzzes", "Steady" to "One long buzz", "Gentle" to "Starts soft, gets stronger")
            Sheet.pick(this, "Vibration", opts, ids.indexOf(alarm.vibePattern).coerceAtLeast(0),
                onTap = { i -> Preview.vibrate(this, ids[i]) }) { i -> alarm.vibePattern = ids[i]; renderVibe() }
        }
        val soundLabel = findViewById<TextView>(R.id.txt_sound)
        soundLabel.text = if (alarm.hasCustomSound(this)) alarm.soundName else "Chime"
        findViewById<View>(R.id.row_sound).setOnClickListener {
            val opts = mutableListOf<Pair<String, String?>>("Chime" to "Built in")
            if (alarm.hasCustomSound(this)) opts.add(alarm.soundName to "From your music")
            opts.add("Choose a file…" to "Songs or recordings on your phone")
            val sel = if (alarm.hasCustomSound(this) && alarm.soundName.isNotEmpty()) 1 else 0
            Sheet.pick(this, "Sound", opts, sel, onTap = { i ->
                when {
                    i == 0 -> Preview.sound(this, android.net.Uri.parse("android.resource://$packageName/${R.raw.chime}"))
                    i < opts.size - 1 && alarm.hasCustomSound(this) -> Preview.sound(this, android.net.Uri.fromFile(alarm.soundFile(this)))
                    else -> Preview.stop()
                }
            }) { i ->
                when {
                    i == 0 -> { alarm.soundName = ""; soundLabel.text = "Chime" }
                    i == opts.size - 1 -> pickSound.launch(arrayOf("audio/*"))
                }
            }
        }
        val voiceLabel = findViewById<TextView>(R.id.txt_voice)
        voiceLabel.text = VoiceEngine.voice(alarm.voice).name
        findViewById<View>(R.id.row_voice).setOnClickListener {
            VoicePicker.show(this, alarm.voice) { id -> alarm.voice = id; voiceLabel.text = VoiceEngine.voice(id).name }
        }

        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_save).setOnClickListener { alarm.enabled = true; AlarmStore.save(this, alarm); finish() }

        renderTime(); renderDays()
    }

    /** Copies the chosen song into app storage so it still plays at 6 am even if the source moves. */
    private val pickSound = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.') ?: "Song"
        Thread {
            val ok = try {
                val f = alarm.soundFile(this); f.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                f.length() > 0
            } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) { alarm.soundName = name; findViewById<TextView>(R.id.txt_sound).text = name }
                else android.widget.Toast.makeText(this, "Couldn't use that file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun onResume() { super.onResume(); renderTomorrow() }

    private fun renderTomorrow() {
        val t = findViewById<TextView>(R.id.txt_tomorrow)
        val i = Brief.intention(this)
        t.text = if (i.isBlank()) "Not set" else i
        t.setTextColor(if (i.isBlank()) Ui.GREY else Ui.TEXT)
    }

    private fun renderLabel() {
        findViewById<TextView>(R.id.txt_label).apply {
            text = alarm.label.ifBlank { "None" }; setTextColor(if (alarm.label.isBlank()) 0xFF8A93A8.toInt() else 0xFF93AEFF.toInt())
        }
    }

    /** Label: a text field plus quick picks. */
    private fun labelSheet() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = android.widget.EditText(this).apply {
            setText(alarm.label); setSelection(text.length); hint = "e.g. Work"; textSize = 17f
            setTextColor(Ui.TEXT); setHintTextColor(Ui.CAPTION); setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            setBackgroundResource(R.drawable.bg_input); setPadding(dp(16), 0, dp(16), 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        col.addView(input, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        val flow = FlowLayout(this, dp(8))
        listOf("Work", "Gym", "School", "Meds", "Run").forEach { t ->
            flow.addView(Ui.text(this, t, 15f, 0xFFB8BFCC.toInt()).apply {
                gravity = Gravity.CENTER; minHeight = dp(40); setPadding(dp(16), 0, dp(16), 0)
                background = android.graphics.drawable.GradientDrawable().apply { setColor(Ui.SEC); cornerRadius = dp(20).toFloat() }
                setOnClickListener { input.setText(t); input.setSelection(t.length) }
            })
        }
        col.addView(flow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val d = Sheet.show(this, "Label", "Shown on the alarm and when it rings", col) {
            alarm.label = input.text.toString().trim(); renderLabel()
        }
        input.setOnEditorActionListener { _, _, _ -> alarm.label = input.text.toString().trim(); renderLabel(); d.dismiss(); true }
    }

    private fun renderReminder() {
        val h = alarm.planHour
        findViewById<TextView>(R.id.txt_reminder).text = "%d:%02d %s the night before".format(if (h % 12 == 0) 12 else h % 12, alarm.planMinute, if (h < 12) "am" else "pm")
    }
    private fun renderVibe() {
        findViewById<TextView>(R.id.txt_vibration).text = alarm.vibePattern.replaceFirstChar { it.uppercase() }
    }

    /** 2.7: time for this alarm's planning reminder, on the same wheels as the alarm. */
    private fun reminderSheet() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24)) }
        val h = WheelView(this); val m = WheelView(this); val ap = WheelView(this)
        h.setRange(1, 12); m.setRange(0, 59); ap.setRange(0, 1); ap.labels = listOf("am", "pm"); ap.wrap = false
        val h12 = alarm.planHour % 12; h.value = if (h12 == 0) 12 else h12; m.value = alarm.planMinute; ap.value = if (alarm.planHour < 12) 0 else 1
        row.addView(h, LinearLayout.LayoutParams(dp(88), dp(200)))
        row.addView(TextView(this).apply { text = ":"; textSize = 34f; setTextColor(Ui.TEXT); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        row.addView(m, LinearLayout.LayoutParams(dp(88), dp(200)))
        row.addView(ap, LinearLayout.LayoutParams(dp(76), dp(200)))
        Sheet.show(this, "Planning reminder", "The night before this alarm", row) {
            alarm.planHour = (h.value % 12) + if (ap.value == 1) 12 else 0; alarm.planMinute = m.value
            alarm.planRemind = true; findViewById<SwitchCompat>(R.id.sw_reminder).isChecked = true
            renderReminder()
        }
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
            tv.setBackgroundResource(if (on) R.drawable.bg_day_on else 0)
            tv.setTextColor(if (on) 0xFF93AEFF.toInt() else 0xFF5E6880.toInt())
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_ID = "id" }
}

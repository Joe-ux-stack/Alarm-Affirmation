package com.whynotyou.alarm

import android.Manifest
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var time: TextView
    private lateinit var sub: TextView
    private lateinit var switchAlarm: SwitchCompat
    private lateinit var intention: EditText
    private lateinit var calendarLink: TextView
    private lateinit var messagesList: LinearLayout
    private lateinit var noMessages: TextView
    private lateinit var warning: TextView
    private lateinit var videoLabel: TextView
    private lateinit var statusLine: TextView
    private lateinit var panelMore: View

    private var pendingFrom: String = ""

    private val pickMessage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val m = MessageStore.import(this, uri, pendingFrom)
        Toast.makeText(this, if (m != null) "Added" else "Couldn't read that file", Toast.LENGTH_SHORT).show()
        refresh()
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        VideoStore.importFrom(this, uri); refresh()
    }
    private val askCalendar = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) CalendarSync.refreshToday(this)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        time = findViewById(R.id.txt_time)
        sub = findViewById(R.id.txt_sub)
        switchAlarm = findViewById(R.id.switch_alarm)
        intention = findViewById(R.id.edit_intention)
        calendarLink = findViewById(R.id.txt_calendar)
        messagesList = findViewById(R.id.list_messages)
        noMessages = findViewById(R.id.txt_no_messages)
        warning = findViewById(R.id.txt_warning)
        videoLabel = findViewById(R.id.txt_video)
        statusLine = findViewById(R.id.txt_status_line)
        panelMore = findViewById(R.id.panel_more)

        intention.setText(Brief.intention(this))

        time.setOnClickListener {
            TimePickerDialog(this, { _, h, m -> AlarmScheduler.setDaily(this, h, m); refresh() },
                AlarmScheduler.hour(this), AlarmScheduler.minute(this), false).show()
        }
        switchAlarm.setOnCheckedChangeListener { _, on ->
            if (on) AlarmScheduler.setDaily(this, AlarmScheduler.hour(this), AlarmScheduler.minute(this))
            else AlarmScheduler.disable(this)
            refresh()
        }
        warning.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        calendarLink.setOnClickListener { askCalendar.launch(Manifest.permission.READ_CALENDAR) }

        findViewById<Button>(R.id.btn_add_message).setOnClickListener { askWhoThenPick() }

        findViewById<TextView>(R.id.btn_more).setOnClickListener {
            panelMore.visibility = if (panelMore.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btn_pick_video).setOnClickListener { pickVideo.launch(arrayOf("video/*", "audio/*")) }
        findViewById<Button>(R.id.btn_reset_video).setOnClickListener { VideoStore.clear(this); refresh() }
        findViewById<Button>(R.id.btn_preview).setOnClickListener {
            startActivity(Intent(this, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_PREVIEW, true))
        }
        findViewById<Button>(R.id.btn_test_1min).setOnClickListener {
            AlarmScheduler.scheduleInMinutes(this, 1)
            Toast.makeText(this, "Lock the phone. Firing in 60 s.", Toast.LENGTH_LONG).show()
            refresh()
        }

        if (savedInstanceState == null && !Permissions.allGranted(this))
            startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun askWhoThenPick() {
        val input = EditText(this).apply {
            hint = "Who is it from? (e.g. Mum)"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Add a message")
            .setMessage("A voice memo or short video. It plays after you stop the alarm.")
            .setView(input)
            .setPositiveButton("Choose file") { _, _ ->
                pendingFrom = input.text.toString().trim()
                pickMessage.launch(arrayOf("audio/*", "video/*"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() { super.onResume(); CalendarSync.refreshToday(this); refresh() }
    override fun onPause() { super.onPause(); Brief.setIntention(this, intention.text.toString()) }

    private fun refresh() {
        // Alarm
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, AlarmScheduler.hour(this@MainActivity)); set(Calendar.MINUTE, AlarmScheduler.minute(this@MainActivity))
        }
        time.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(cal.time) +
            " " + SimpleDateFormat("a", Locale.getDefault()).format(cal.time).lowercase()
        switchAlarm.setOnCheckedChangeListener(null)
        switchAlarm.isChecked = AlarmScheduler.isEnabled(this)
        switchAlarm.setOnCheckedChangeListener { _, on ->
            if (on) AlarmScheduler.setDaily(this, AlarmScheduler.hour(this), AlarmScheduler.minute(this)) else AlarmScheduler.disable(this)
            refresh()
        }
        val next = AlarmScheduler.nextTime(this)
        sub.text = when {
            next != null -> "Every day · next " + SimpleDateFormat("EEE", Locale.getDefault()).format(Date(next)) + " · " + untilText(next)
            else -> "Off · tap the time to change"
        }
        time.alpha = if (AlarmScheduler.isEnabled(this)) 1f else 0.4f

        // Calendar
        calendarLink.text = if (CalendarSync.hasPermission(this)) {
            val n = Brief.cachedEvents(this).size
            "✓ Calendar · $n thing${if (n == 1) "" else "s"} today"
        } else "Read my calendar too →"
        calendarLink.isClickable = !CalendarSync.hasPermission(this)
        calendarLink.setTextColor(if (CalendarSync.hasPermission(this)) 0xFF6B7488.toInt() else 0xFFF5A524.toInt())

        // Messages
        messagesList.removeAllViews()
        val msgs = MessageStore.all(this)
        noMessages.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
        val nextMsg = MessageStore.peekNext(this)
        msgs.forEach { m ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 28, 0, 28)
            }
            val label = TextView(this).apply {
                text = (if (m.from.isNotBlank()) m.from else m.name) +
                    "  ·  " + "%d:%02d".format(m.durationMs / 60000, (m.durationMs / 1000) % 60) +
                    (if (m == nextMsg && msgs.size > 1) "  ·  next" else "")
                setTextColor(0xFFF2F4F8.toInt()); textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = TextView(this).apply {
                text = "Remove"; setTextColor(0xFF6B7488.toInt()); textSize = 13f
                minHeight = 44; setPadding(24, 0, 0, 0)
                setOnClickListener { MessageStore.remove(this@MainActivity, m.id); refresh() }
            }
            row.addView(label); row.addView(del)
            messagesList.addView(row)
        }

        // More
        videoLabel.text = "Alarm sound: " + (VideoStore.customName(this) ?: "bundled clip")
        val missing = Permissions.missingCount(this)
        warning.visibility = if (missing == 0) View.GONE else View.VISIBLE
        warning.text = "$missing permission${if (missing > 1) "s" else ""} missing — the alarm may not fire. Fix →"
        statusLine.text = if (missing == 0) "✓ Phone is set up to wake you" else ""
    }

    private fun untilText(t: Long): String {
        val mins = ((t - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        return if (mins >= 60) "in ${mins / 60}h ${mins % 60}m" else "in ${mins}m"
    }
}

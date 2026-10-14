package com.whynotyou.alarm

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Home: next alarm, the chosen video, and a 1-minute test. Setup lives on its own screen. */
class MainActivity : AppCompatActivity() {

    private lateinit var nextTime: TextView
    private lateinit var nextSub: TextView
    private lateinit var videoName: TextView
    private lateinit var videoSub: TextView
    private lateinit var warning: View
    private lateinit var warningText: TextView
    private lateinit var statusLine: TextView
    private lateinit var cancelBtn: Button
    private lateinit var resetVideoBtn: Button

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = VideoStore.importFrom(this, uri)
        Toast.makeText(this, if (ok) "Video saved" else "Couldn't read that file", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nextTime = findViewById(R.id.txt_next_time)
        nextSub = findViewById(R.id.txt_next_sub)
        videoName = findViewById(R.id.txt_video)
        videoSub = findViewById(R.id.txt_video_sub)
        warning = findViewById(R.id.row_warning)
        warningText = findViewById(R.id.txt_warning)
        statusLine = findViewById(R.id.txt_status_line)
        cancelBtn = findViewById(R.id.btn_cancel)
        resetVideoBtn = findViewById(R.id.btn_reset_video)

        warning.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        findViewById<Button>(R.id.btn_set_time).setOnClickListener {
            val now = Calendar.getInstance()
            TimePickerDialog(this, { _, h, m ->
                AlarmScheduler.scheduleAtTimeOfDay(this, h, m)
                refresh()
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }
        cancelBtn.setOnClickListener { AlarmScheduler.cancel(this); refresh() }

        findViewById<Button>(R.id.btn_preview).setOnClickListener {
            startActivity(Intent(this, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_PREVIEW, true))
        }
        findViewById<Button>(R.id.btn_pick_video).setOnClickListener { pickVideo.launch(arrayOf("video/*")) }
        resetVideoBtn.setOnClickListener { VideoStore.clear(this); refresh() }

        findViewById<Button>(R.id.btn_test_1min).setOnClickListener {
            AlarmScheduler.scheduleInMinutes(this, 1)
            Toast.makeText(this, "Lock the phone now. Firing in 60 s.", Toast.LENGTH_LONG).show()
            refresh()
        }

        // First run: go straight to setup.
        if (savedInstanceState == null && !Permissions.allGranted(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val next = AlarmScheduler.nextTime(this)
        if (next == null) {
            nextTime.text = "None"
            nextSub.text = "Tap below to set one"
            cancelBtn.visibility = View.GONE
        } else {
            nextTime.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(next))
            nextSub.text = SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date(next)) +
                " · " + untilText(next)
            cancelBtn.visibility = View.VISIBLE
        }

        val custom = VideoStore.customName(this)
        videoName.text = custom ?: "Bundled placeholder"
        videoSub.text = formatDuration(VideoStore.playbackDurationMs(this)) + if (custom != null) " · from your phone" else ""
        resetVideoBtn.visibility = if (custom != null) View.VISIBLE else View.GONE

        val missing = Permissions.missingCount(this)
        if (missing == 0) {
            warning.visibility = View.GONE
            statusLine.text = "✓ Phone is set up to wake you"
        } else {
            warning.visibility = View.VISIBLE
            warningText.text = "$missing permission${if (missing > 1) "s" else ""} missing — the alarm may not fire"
            statusLine.text = ""
        }
    }

    private fun untilText(t: Long): String {
        val mins = ((t - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        val h = mins / 60; val m = mins % 60
        return if (h > 0) "in ${h}h ${m}m" else "in ${m}m"
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

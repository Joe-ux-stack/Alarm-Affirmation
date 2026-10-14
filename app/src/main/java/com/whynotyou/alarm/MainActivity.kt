package com.whynotyou.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** MVP control panel: permissions checklist, "fire in 1 min" test, and a real time-of-day alarm. */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var permNotif: Button
    private lateinit var permExact: Button
    private lateinit var permBattery: Button
    private lateinit var permOverlay: Button
    private lateinit var videoLabel: TextView

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = VideoStore.importFrom(this, uri)
        Toast.makeText(this, if (ok) "Video saved" else "Couldn't read that file", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.txt_status)
        permNotif = findViewById(R.id.btn_perm_notif)
        permExact = findViewById(R.id.btn_perm_exact)
        permBattery = findViewById(R.id.btn_perm_battery)
        permOverlay = findViewById(R.id.btn_perm_overlay)
        videoLabel = findViewById(R.id.txt_video)

        findViewById<Button>(R.id.btn_pick_video).setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }
        findViewById<Button>(R.id.btn_reset_video).setOnClickListener {
            VideoStore.clear(this)
            refresh()
        }

        permNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                )
            }
        }
        permExact.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            }
        }
        permBattery.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        permOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        findViewById<Button>(R.id.btn_test_1min).setOnClickListener {
            AlarmScheduler.scheduleInMinutes(this, 1)
            refresh()
        }
        findViewById<Button>(R.id.btn_test_now).setOnClickListener {
            startActivity(Intent(this, AlarmActivity::class.java))
        }
        findViewById<Button>(R.id.btn_set_time).setOnClickListener {
            val now = Calendar.getInstance()
            TimePickerDialog(this, { _, h, m ->
                AlarmScheduler.scheduleAtTimeOfDay(this, h, m)
                refresh()
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            AlarmScheduler.cancel(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val next = AlarmScheduler.nextTime(this)
        status.text = if (next == null) "No alarm set" else {
            val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
            "Next alarm: ${fmt.format(Date(next))}"
        }

        videoLabel.text = VideoStore.customName(this)?.let { "Playing: $it" } ?: "Playing: bundled placeholder"

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val exactOk = AlarmScheduler.canScheduleExact(this)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val overlayOk = Settings.canDrawOverlays(this)

        mark(permNotif, "Notifications", notifOk)
        mark(permExact, "Exact alarms", exactOk)
        mark(permBattery, "Ignore battery optimisation", batteryOk)
        mark(permOverlay, "Show over other apps", overlayOk)
    }

    private fun mark(b: Button, label: String, ok: Boolean) {
        b.text = (if (ok) "✓  " else "!  ") + label
        b.isEnabled = !ok
    }
}

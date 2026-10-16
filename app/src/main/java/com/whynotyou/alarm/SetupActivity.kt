package com.whynotyou.alarm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/** First-run checklist. Each row disappears into a ✓ once granted; Continue enables when all are. */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        findViewById<Button>(R.id.btn_notif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33)
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        findViewById<Button>(R.id.btn_exact).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31)
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_continue).setOnClickListener {
            if (!Onboarding.trialShown(this)) startActivity(android.content.Intent(this, TrialActivity::class.java))
            finish()
        }
        info(R.id.info_notif, R.id.txt_notif); info(R.id.info_exact, R.id.txt_exact)
        info(R.id.info_battery, R.id.txt_battery); info(R.id.info_overlay, R.id.txt_overlay)
    }

    override fun onResume() {
        super.onResume()
        row(R.id.row_notif, R.id.btn_notif, R.id.tick_notif, Permissions.notifications(this))
        row(R.id.row_exact, R.id.btn_exact, R.id.tick_exact, Permissions.exactAlarms(this))
        row(R.id.row_battery, R.id.btn_battery, R.id.tick_battery, Permissions.battery(this))
        row(R.id.row_overlay, R.id.btn_overlay, R.id.tick_overlay, Permissions.overlay(this))

        val all = Permissions.allGranted(this)
        findViewById<Button>(R.id.btn_continue).apply {
            isEnabled = all
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (all) Ui.BLUE else Ui.SEC)
            setTextColor(if (all) 0xFFFFFFFF.toInt() else 0xFF6F7EA6.toInt())
            text = if (all) "Continue" else "${Permissions.missingCount(this@SetupActivity)} left"
        }
    }

    private fun info(btn: Int, txt: Int) {
        val t = findViewById<View>(txt)
        findViewById<View>(btn).setOnClickListener { t.visibility = if (t.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
    }

    private fun row(rowId: Int, btnId: Int, tickId: Int, ok: Boolean) {
        findViewById<View>(rowId).alpha = if (ok) 0.55f else 1f
        findViewById<Button>(btnId).visibility = if (ok) View.GONE else View.VISIBLE
        findViewById<TextView>(tickId).visibility = if (ok) View.VISIBLE else View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onResume()
    }
}

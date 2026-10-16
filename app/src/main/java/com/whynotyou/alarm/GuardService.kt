package com.whynotyou.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton

/**
 * Watches which app is in front (Usage Access) every few seconds while the screen is on. Time spent
 * in watched apps adds up into one "session"; 2 minutes away resets it. When the session passes the
 * limit, a full-screen card is drawn over the app: the orb, a message, "Close" and "5 more minutes".
 */
class GuardService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var lastQuery = 0L
    private var lastTick = 0L
    private var sessionMs = 0L
    private var awaySince = 0L
    private var snoozeUntil = 0L
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIF_ID, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, notification())
        if (lastQuery == 0L) lastQuery = System.currentTimeMillis() - 60 * 60_000L
        lastTick = System.currentTimeMillis()
        ui.removeCallbacks(tick); ui.post(tick)
        return START_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            try { step() } catch (_: Throwable) {}
            val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            ui.postDelayed(this, if (interactive) 4_000L else 30_000L)
        }
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val dt = (now - lastTick).coerceIn(0L, 10_000L); lastTick = now
        if (!PhoneDown.isEnabled(this)) { stopSelf(); return }
        val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        updateForeground(now)
        val pkg = current
        val watched = interactive && pkg != null && pkg in PhoneDown.effectiveWatched(this)
        if (watched) {
            sessionMs += dt; awaySince = 0L
            val limit = PhoneDown.limitMin(this) * 60_000L
            if ((sessionMs >= limit && now >= snoozeUntil || PhoneDown.blockedToday(this, pkg!!)) && overlay == null) showOverlay(pkg)
        } else {
            if (awaySince == 0L) awaySince = now
            if (now - awaySince > 2 * 60_000L) sessionMs = 0L
            if (overlay != null && pkg != null && pkg != packageName && pkg !in PhoneDown.effectiveWatched(this)) removeOverlay()
        }
    }

    /** Replays usage events since the last poll to know which app is in front. */
    private fun updateForeground(now: Long) {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = usm.queryEvents(lastQuery, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == current) current = null
            }
        }
        lastQuery = now
    }

    private fun showOverlay(pkg: String) {
        if (!PhoneDown.canDrawOver(this)) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val mins = PhoneDown.limitMin(this)
        val app = PhoneDown.appName(this, pkg)
        val strict = PhoneDown.isStrict(this)
        if (strict) PhoneDown.blockForToday(this, pkg)

        val root = FrameLayout(this).apply { setBackgroundColor(0xF5060B18.toInt()) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(96), dp(24), dp(48))
        }
        col.addView(TextView(this).apply {
            text = if (strict) "$mins min on $app · Strict mode" else "$mins min on $app"; textSize = 14f; setTextColor(0xFF8A93A8.toInt())
        })
        col.addView(OrbView(this).apply { mode = OrbView.Mode.BREATHE },
            LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(40) })
        col.addView(TextView(this).apply {
            text = if (strict) "That's your $mins minutes." else PhoneDown.nextMessage(this@GuardService); textSize = 28f; setTextColor(0xFFF2F4F8.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(36) })
        if (strict) col.addView(TextView(this).apply {
            text = "$app is closed until tomorrow."; textSize = 16f; setTextColor(0xFF8A93A8.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(48)) }
        val close = MaterialButton(this).apply {
            text = if (strict) "Close $app" else "Put it down"; isAllCaps = false; letterSpacing = 0f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); textSize = 18f; setTextColor(0xFFFFFFFF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF3461FF.toInt()); cornerRadius = dp(32)
            setOnClickListener {
                sessionMs = 0L; removeOverlay()
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val more = MaterialButton(this).apply {
            text = "5 more minutes"; isAllCaps = false; letterSpacing = 0f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL); textSize = 16f; setTextColor(0xFFC9D4FF.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0x292D5BFF); cornerRadius = dp(28)
            setOnClickListener { snoozeUntil = System.currentTimeMillis() + 5 * 60_000L; removeOverlay() }
        }
        bottom.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
        if (!strict) bottom.addView(more, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(8) })
        root.addView(bottom, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try { wm.addView(root, lp); overlay = root } catch (_: Throwable) {}
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Throwable) {}
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Phone down", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 3, Intent(this, PhoneDownActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Phone down is on")
            .setContentText("${PhoneDown.limitMin(this)} min limit")
            .setOngoing(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() { removeOverlay(); ui.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        const val CHANNEL = "phonedown"; const val NOTIF_ID = 88; const val ACTION_STOP = "stop"
        fun start(c: Context) {
            if (!PhoneDown.isEnabled(c) || !PhoneDown.hasUsageAccess(c)) return
            try { c.startForegroundService(Intent(c, GuardService::class.java)) } catch (_: Throwable) {}
        }
        fun stop(c: Context) { c.stopService(Intent(c, GuardService::class.java)) }
    }
}

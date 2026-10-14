package com.whynotyou.alarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The screen that appears when the alarm fires: full-screen video on the alarm audio stream,
 * looping until "I'm up". Shows over the lock screen and turns the screen on.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var video: VideoView
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume = -1
    private var completedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)

        // Show over lock screen + turn screen on (manifest flags cover API 27+, these cover older).
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }

        // Immersive: hide status / nav bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Make sure the CPU stays up while we play.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "whynotyou:alarm"
        ).also { it.acquire(10 * 60_000L) }

        // Force alarm stream to max so a muted phone still wakes you.
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(
            AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
        )

        val dismiss = findViewById<Button>(R.id.btn_dismiss)
        val hint = findViewById<TextView>(R.id.txt_hint)
        video = findViewById(R.id.video)

        video.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.wakeup}"))
        video.setOnPreparedListener { mp ->
            mp.isLooping = true
            video.start()
        }
        video.setOnInfoListener { _, _, _ -> false }

        // "Must watch to the end": dismiss unlocks after the first full play.
        dismiss.isEnabled = false
        dismiss.alpha = 0.4f
        video.postDelayed({ unlockDismiss(dismiss, hint) }, PLACEHOLDER_LENGTH_MS)

        dismiss.setOnClickListener { finishAlarm() }
    }

    private fun unlockDismiss(dismiss: Button, hint: TextView) {
        completedOnce = true
        dismiss.isEnabled = true
        dismiss.alpha = 1f
        hint.visibility = View.INVISIBLE
    }

    private fun finishAlarm() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(AlarmReceiver.NOTIFICATION_ID)
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        video.stopPlayback()
        if (savedAlarmVolume >= 0) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    // Back button does nothing: you dismiss by pressing the button.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { }

    companion object {
        /** Length of the bundled clip; replace with the real duration once the video is swapped. */
        private const val PLACEHOLDER_LENGTH_MS = 10_000L
    }
}

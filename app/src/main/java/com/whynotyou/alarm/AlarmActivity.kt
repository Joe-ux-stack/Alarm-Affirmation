package com.whynotyou.alarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * The screen that appears when the alarm fires: full-screen video on the alarm audio stream,
 * looping until "I'm up". Shows over the lock screen and turns the screen on.
 * Opened with EXTRA_PREVIEW=true it behaves as a preview: dismiss is enabled immediately.
 */
class AlarmActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume = -1
    private var unlocked = false
    private var speaker: BriefSpeaker? = null
    private var briefStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)
        val preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (!preview) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "whynotyou:alarm"
            ).also { it.acquire(10 * 60_000L) }

            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }

        val dismiss = findViewById<Button>(R.id.btn_dismiss)
        val hint = findViewById<TextView>(R.id.txt_hint)
        val error = findViewById<TextView>(R.id.txt_error)
        val playerView = findViewById<PlayerView>(R.id.player)

        val exo = ExoPlayer.Builder(this).build().also { player = it }
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_ALARM)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false
        )
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.setMediaItem(MediaItem.fromUri(VideoStore.playbackUri(this)))
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error.text = "Couldn't play this video (${e.errorCodeName}).\nTry another file, ideally an .mp4."
                error.visibility = View.VISIBLE
                unlock(dismiss, hint)
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // First loop completed → the viewer has seen the whole message.
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) startBrief(dismiss, hint)
            }
        })
        playerView.player = exo
        playerView.useController = false
        exo.prepare()

        if (preview) {
            unlock(dismiss, hint)
            dismiss.text = "Close preview"
        } else {
            dismiss.isEnabled = false
            dismiss.alpha = 0.4f
            // Safety net in case the discontinuity callback never fires.
            dismiss.postDelayed({ startBrief(dismiss, hint) }, VideoStore.playbackDurationMs(this) + 1_500L)
        }

        dismiss.setOnClickListener { finishAlarm() }
    }

    /** After the video's first play: lower the video, read the day, then unlock. */
    private fun startBrief(dismiss: Button, hint: TextView) {
        if (briefStarted) return
        briefStarted = true
        val text = Brief.compose(this)
        if (text.isBlank()) { unlock(dismiss, hint); return }
        player?.volume = 0.15f
        hint.text = "Listen…"
        val briefView = findViewById<TextView>(R.id.txt_brief)
        briefView.text = text.replace("Phone down.", "").trim()
        briefView.visibility = View.VISIBLE
        speaker = BriefSpeaker(this) {
            runOnUiThread {
                player?.volume = 1f
                briefView.text = "Phone down."
                unlock(dismiss, hint)
                Brief.clearIntention(this)
            }
        }.also { it.speak(text) }
    }

    private fun unlock(dismiss: Button, hint: TextView) {
        if (unlocked) return
        unlocked = true
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
        speaker?.release()
        player?.release()
        player = null
        if (savedAlarmVolume >= 0) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_PREVIEW, false)) finishAlarm()
    }

    companion object {
        const val EXTRA_PREVIEW = "preview"
    }
}

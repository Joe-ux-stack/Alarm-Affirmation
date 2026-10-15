package com.whynotyou.alarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flow: RINGING (wake-up clip loops, big "Stop") → MESSAGE (a loved one's recording, then the
 * spoken day brief) → DONE ("I'm up"). Shows over the lock screen, turns the screen on.
 */
class AlarmActivity : AppCompatActivity() {

    private enum class Phase { RINGING, MESSAGE, DONE }
    private var phase = Phase.RINGING

    private var player: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume = -1
    private var speaker: BriefSpeaker? = null
    private var preview = false

    private lateinit var btn: Button
    private lateinit var hint: TextView
    private lateinit var clock: TextView
    private lateinit var briefView: TextView
    private lateinit var fromView: TextView
    private lateinit var snooze: Button
    private lateinit var playerView: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        if (!preview) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "whynotyou:alarm"
            ).also { it.acquire(10 * 60_000L) }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            CalendarSync.refreshToday(this)
        }

        btn = findViewById(R.id.btn_dismiss)
        hint = findViewById(R.id.txt_hint)
        clock = findViewById(R.id.txt_clock)
        briefView = findViewById(R.id.txt_brief)
        fromView = findViewById(R.id.txt_from)
        snooze = findViewById(R.id.btn_snooze)
        playerView = findViewById(R.id.player)

        clock.text = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date())

        val exo = ExoPlayer.Builder(this).build().also { player = it }
        exo.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false
        )
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) { if (phase == Phase.MESSAGE) speakBrief() }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && phase == Phase.MESSAGE) speakBrief()
            }
        })
        playerView.player = exo
        playerView.useController = false

        snooze.setOnClickListener { doSnooze() }
        btn.setOnClickListener {
            when (phase) {
                Phase.RINGING -> startMessage()
                Phase.MESSAGE -> { /* locked while the message plays */ }
                Phase.DONE -> finishAlarm()
            }
        }

        enterRinging()
    }

    // ---- Phase 1: ringing -------------------------------------------------------------------

    private fun enterRinging() {
        phase = Phase.RINGING
        val exo = player ?: return
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.setMediaItem(MediaItem.fromUri(VideoStore.playbackUri(this)))
        exo.playWhenReady = true
        exo.prepare()
        btn.text = "Stop"
        hint.text = "Stop the alarm to hear your message"
        snooze.visibility = if (preview || AlarmScheduler.snoozesLeft(this) == 0) View.GONE else View.VISIBLE
        snooze.text = "Snooze 5 min · ${AlarmScheduler.snoozesLeft(this)} left"
    }

    // ---- Phase 2: the message ---------------------------------------------------------------

    private fun startMessage() {
        phase = Phase.MESSAGE
        snooze.visibility = View.GONE
        btn.isEnabled = false; btn.alpha = 0.4f
        btn.text = "I'm up"
        hint.text = "Listen…"

        val msg = if (preview) MessageStore.peekNext(this) else MessageStore.pickForToday(this)
        val exo = player ?: return
        if (msg != null) {
            fromView.text = if (msg.from.isNotBlank()) "From ${msg.from}" else "A message for you"
            fromView.visibility = View.VISIBLE
            playerView.visibility = if (msg.isVideo) View.VISIBLE else View.INVISIBLE
            exo.repeatMode = Player.REPEAT_MODE_OFF
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(msg.file(this))))
            exo.playWhenReady = true
            exo.prepare()
            // onPlaybackStateChanged(ENDED) → speakBrief()
        } else {
            exo.stop()
            playerView.visibility = View.INVISIBLE
            speakBrief()
        }
    }

    private var briefSpoken = false
    private fun speakBrief() {
        if (briefSpoken) return
        briefSpoken = true
        val text = Brief.compose(this)
        if (text.isBlank()) { enterDone(); return }
        briefView.text = text.replace("Phone down.", "").trim()
        briefView.visibility = View.VISIBLE
        speaker = BriefSpeaker(this) { runOnUiThread { enterDone() } }.also { it.speak(text) }
    }

    // ---- Phase 3: done ----------------------------------------------------------------------

    private fun enterDone() {
        phase = Phase.DONE
        briefView.text = "Phone down."
        briefView.visibility = View.VISIBLE
        btn.isEnabled = true; btn.alpha = 1f
        btn.text = if (preview) "Close preview" else "I'm up"
        hint.visibility = View.INVISIBLE
        if (!preview) Brief.clearIntention(this)
    }

    private fun doSnooze() {
        AlarmScheduler.snooze(this, 5)
        finishAlarm()
    }

    private fun finishAlarm() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(AlarmReceiver.NOTIFICATION_ID)
        if (!preview && phase == Phase.DONE) AlarmScheduler.onDismissed(this)
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        speaker?.release()
        player?.release(); player = null
        if (savedAlarmVolume >= 0)
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (preview) finishAlarm() }

    companion object { const val EXTRA_PREVIEW = "preview" }
}

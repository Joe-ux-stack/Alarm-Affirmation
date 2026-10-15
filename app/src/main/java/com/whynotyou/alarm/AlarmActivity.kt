package com.whynotyou.alarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * RINGING (chime loops, orb breathes, "Stop") → MESSAGE (loved one's recording, orb pulses,
 * "I'm up" always tappable) → BRIEF (spoken day, orb pulses) → DONE ("Phone down." + "I'm up").
 * Also used for timer end (EXTRA_TIMER) and preview (EXTRA_PREVIEW).
 */
class AlarmActivity : AppCompatActivity() {

    private enum class Phase { RINGING, MESSAGE, BRIEF, DONE }
    private var phase = Phase.RINGING

    private lateinit var alarm: Alarm
    private var preview = false
    private var timerMode = false

    private var player: ExoPlayer? = null
    private var visualizer: Visualizer? = null
    private var speaker: BriefSpeaker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume = -1
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var btn: Button
    private lateinit var snooze: Button
    private lateinit var clock: TextView
    private lateinit var sub: TextView
    private lateinit var briefView: TextView
    private lateinit var phoneDown: TextView
    private lateinit var orb: OrbView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        timerMode = intent.getBooleanExtra(EXTRA_TIMER, false)
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: AlarmScheduler.TEST_ID
        alarm = AlarmStore.get(this, id) ?: Alarm.new()

        showOverLockScreen()

        btn = findViewById(R.id.btn_main); snooze = findViewById(R.id.btn_snooze)
        clock = findViewById(R.id.txt_clock); sub = findViewById(R.id.txt_sub)
        briefView = findViewById(R.id.txt_brief); phoneDown = findViewById(R.id.txt_phone_down)
        orb = findViewById(R.id.orb)

        clock.text = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date())
        sub.text = if (timerMode) "Timer" else SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())

        if (!preview) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "whynotyou:alarm")
                .also { it.acquire(10 * 60_000L) }
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val pct = if (timerMode) TimerSettings.volume(this) else alarm.volume
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * pct / 100f).toInt().coerceAtLeast(1), 0)
            if (!timerMode) CalendarSync.refreshToday(this)
        }

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) { if (phase == Phase.MESSAGE) startBrief() }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED && phase == Phase.MESSAGE) startBrief() }
            })
        }

        snooze.setOnClickListener { AlarmScheduler.snooze(this, alarm.id); finishAlarm(false) }
        btn.setOnClickListener {
            when (phase) {
                Phase.RINGING -> if (timerMode) finishAlarm(false) else startMessage()
                else -> finishAlarm(true)
            }
        }
        enterRinging()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---- RINGING ---------------------------------------------------------------------------
    private fun enterRinging() {
        phase = Phase.RINGING
        orb.mode = OrbView.Mode.BREATHE
        btn.text = if (timerMode) "Done" else "Stop"
        val canSnooze = !preview && !timerMode && alarm.snooze && AlarmScheduler.snoozesLeft(this) > 0
        snooze.visibility = if (canSnooze) View.VISIBLE else View.GONE
        snooze.text = "Snooze 5 min · ${AlarmScheduler.snoozesLeft(this)} left"

        val p = player ?: return
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://$packageName/${R.raw.chime}")))
        p.volume = if (alarm.ramp && !preview) 0.15f else 1f
        p.playWhenReady = true
        p.prepare()
        if (alarm.ramp && !preview) rampVolume()
    }

    private fun rampVolume() {
        val steps = 20; val total = 10_000L
        for (i in 1..steps) ui.postDelayed({
            if (phase == Phase.RINGING) player?.volume = 0.15f + 0.85f * i / steps
        }, total * i / steps)
    }

    // ---- MESSAGE ---------------------------------------------------------------------------
    private fun startMessage() {
        phase = Phase.MESSAGE
        snooze.visibility = View.GONE
        btn.text = "I'm up"
        orb.mode = OrbView.Mode.SPEAK

        val msg = if (alarm.message) (if (preview) MessageStore.peekNext(this) else MessageStore.pickForToday(this)) else null
        val p = player ?: return
        if (msg != null) {
            sub.text = if (msg.from.isNotBlank()) "From ${msg.from}" else "A message for you"
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.volume = 1f
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(msg.file(this))))
            p.playWhenReady = true
            p.prepare()
            attachVisualizer(p.audioSessionId)
        } else {
            p.stop()
            startBrief()
        }
    }

    private var peak = 0.02

    private fun attachVisualizer(sessionId: Int) {
        peak = 0.02
        try {
            visualizer?.release()
            visualizer = Visualizer(sessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) {
                        if (wave == null) return
                        var sum = 0.0
                        for (b in wave) { val s = (b.toInt() and 0xFF) - 128; sum += s * s }
                        val rms = sqrt(sum / wave.size) / 128.0
                        // auto-gain: scale to the loudest thing heard so far (slowly forgets), so quiet
                        // recordings still swing the orb through its full range
                        peak = maxOf(peak * 0.995, rms, 0.02)
                        val norm = (rms / peak).coerceIn(0.0, 1.0)
                        orb.setLevel(Math.pow(norm, 0.7).toFloat())
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {}
                }, Visualizer.getMaxCaptureRate() / 2, true, false)
                enabled = true
            }
        } catch (e: Exception) { pulseFallback() }
    }

    /** If the Visualizer isn't permitted (needs RECORD_AUDIO on some OEMs), fake a gentle pulse. */
    private fun pulseFallback() {
        val r = object : Runnable {
            var t = 0f
            override fun run() {
                if (phase == Phase.MESSAGE || phase == Phase.BRIEF) {
                    t += 0.25f
                    orb.setLevel(0.4f + 0.4f * abs(kotlin.math.sin(t)))
                    ui.postDelayed(this, 120)
                }
            }
        }
        ui.post(r)
    }

    // ---- BRIEF -----------------------------------------------------------------------------
    private var briefStarted = false
    private fun startBrief() {
        if (briefStarted) return
        briefStarted = true
        phase = Phase.BRIEF
        visualizer?.release(); visualizer = null
        player?.stop()
        val text = if (alarm.brief) Brief.compose(this) else ""
        if (text.isBlank()) { enterDone(); return }
        sub.text = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
        briefView.text = text.replace("Phone down.", "").trim()
        briefView.visibility = View.VISIBLE
        orb.layoutParams = orb.layoutParams.apply { width = dp(120); height = dp(120) }

        val cached = BriefAudio.cached(this, alarm.id, text, alarm.voice)
        if (cached != null) { playBriefFile(cached); return }

        // Not cached (first run / calendar changed). Try to render now, but don't keep the user waiting.
        pulseFallback()
        var done = false
        Thread {
            val f = BriefAudio.render(this, alarm.id, text, alarm.voice)
            runOnUiThread { if (!done && !isFinishing) { done = true; if (f != null) playBriefFile(f) else speakWithPhone(text) } }
        }.start()
        ui.postDelayed({ if (!done && !isFinishing) { done = true; speakWithPhone(text) } }, 25_000L)
    }

    private fun playBriefFile(f: java.io.File) {
        val p = player ?: return
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.volume = 1f
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        p.playWhenReady = true
        p.prepare()
        attachVisualizer(p.audioSessionId)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED && phase == Phase.BRIEF) { p.removeListener(this); enterDone() } }
            override fun onPlayerError(e: PlaybackException) { if (phase == Phase.BRIEF) { p.removeListener(this); speakWithPhone(Brief.compose(this@AlarmActivity)) } }
        })
    }

    private fun speakWithPhone(text: String) {
        attachVisualizer(0)   // session 0 = the phone's output mix, so the orb follows the TTS voice too
        speaker = BriefSpeaker(this) { runOnUiThread { enterDone() } }.also { it.speak(text) }
    }

    // ---- DONE ------------------------------------------------------------------------------
    private fun enterDone() {
        phase = Phase.DONE
        orb.mode = OrbView.Mode.STILL
        btn.text = if (preview) "Close" else "I'm up"
        if (!preview) Brief.clearIntention(this)
    }

    private fun finishAlarm(realDismiss: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(AlarmReceiver.NOTIFICATION_ID)
        if (!preview && !timerMode && realDismiss) AlarmScheduler.onDismissed(this, alarm.id)
        finishAndRemoveTask()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        visualizer?.release()
        speaker?.release()
        player?.release(); player = null
        if (savedAlarmVolume >= 0)
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (preview || timerMode) finishAlarm(false) }

    companion object {
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_TIMER = "timer"
    }
}

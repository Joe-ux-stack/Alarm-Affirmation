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
 * RINGING (chime loops, orb breathes, swipe the circle to stop, − snooze +) → MESSAGE (loved one's
 * recording, orb pulses) → BRIEF (spoken day; each sentence appears as it is said) → DONE (the
 * screen fades out on its own). Also used for timer end (EXTRA_TIMER) and preview (EXTRA_PREVIEW).
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

    private lateinit var snooze: Button
    private lateinit var dismiss: SwipeDismissView
    private lateinit var ringControls: View
    private lateinit var snoozeRow: View
    private var snoozeMin = 5
    private lateinit var clock: TextView
    private lateinit var sub: TextView
    private lateinit var briefView: TextView
    private lateinit var orb: OrbView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        timerMode = intent.getBooleanExtra(EXTRA_TIMER, false)
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: AlarmScheduler.TEST_ID
        alarm = AlarmStore.get(this, id) ?: Alarm.new()

        showOverLockScreen()
        if (!preview && !timerMode) AlarmScheduler.onShown(this)

        snooze = findViewById(R.id.btn_snooze); dismiss = findViewById(R.id.btn_dismiss)
        ringControls = findViewById(R.id.ring_controls); snoozeRow = findViewById(R.id.row_snooze)
        clock = findViewById(R.id.txt_clock); sub = findViewById(R.id.txt_sub)
        briefView = findViewById(R.id.txt_brief)
        orb = findViewById(R.id.orb)

        val clockFmt = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(this)) "H:mm" else "h:mm", Locale.getDefault())
        val clockTick = object : Runnable { override fun run() { clock.text = clockFmt.format(Date()); ui.postDelayed(this, 10_000) } }
        clockTick.run()
        sub.text = if (timerMode) "Timer" else dayLine()

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

        snooze.setOnClickListener { AlarmScheduler.snooze(this, alarm.id, snoozeMin); finishAlarm(false) }
        findViewById<View>(R.id.btn_snooze_minus).setOnClickListener { snoozeMin = (snoozeMin - 5).coerceAtLeast(5); renderSnooze() }
        findViewById<View>(R.id.btn_snooze_plus).setOnClickListener { snoozeMin = (snoozeMin + 5).coerceAtMost(30); renderSnooze() }
        dismiss.onDismiss = { if (phase == Phase.RINGING) { if (timerMode) finishAlarm(false) else startMessage() } }
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
        ringControls.visibility = View.VISIBLE
        val canSnooze = !preview && !timerMode && alarm.snooze && AlarmScheduler.snoozesLeft(this) > 0
        snoozeRow.visibility = if (canSnooze) View.VISIBLE else View.INVISIBLE
        renderSnooze()

        val p = player ?: return
        p.repeatMode = Player.REPEAT_MODE_ONE
        val song = if (!timerMode && alarm.hasCustomSound(this)) Uri.fromFile(alarm.soundFile(this)) else null
        p.setMediaItem(MediaItem.fromUri(song ?: Uri.parse("android.resource://$packageName/${R.raw.chime}")))
        p.volume = if (alarm.ramp && !preview) 0.15f else 1f
        p.playWhenReady = true
        p.prepare()
        if (alarm.ramp && !preview) rampVolume()
        if (alarm.vibrate && !preview && !timerMode) startVibration()
        orb.mode = OrbView.Mode.SPEAK
        followFile(song ?: Uri.parse("android.resource://$packageName/${R.raw.chime}"), Phase.RINGING)
    }

    private var vibrator: android.os.Vibrator? = null
    /** Pulse: 0.5 s on, 1 s off, repeating while it rings. */
    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                   else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
        val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
        val wave = when (alarm.vibePattern) {
            "heartbeat" -> longArrayOf(0, 120, 120, 120, 900)
            "steady" -> longArrayOf(0, 1500, 400)
            "gentle" -> longArrayOf(0, 200, 1400, 300, 1200, 450, 1000, 600, 800)
            else -> longArrayOf(0, 500, 1000)
        }
        val repeatAt = if (alarm.vibePattern == "gentle") 7 else 0
        try { vibrator?.vibrate(android.os.VibrationEffect.createWaveform(wave, repeatAt), attrs) } catch (_: Throwable) {}
    }
    private fun stopVibration() { try { vibrator?.cancel() } catch (_: Throwable) {}; vibrator = null }

    private fun renderSnooze() { snooze.text = "Snooze $snoozeMin mins" }

    private fun rampVolume() {
        val steps = 20; val total = 10_000L
        for (i in 1..steps) ui.postDelayed({
            if (phase == Phase.RINGING) player?.volume = 0.15f + 0.85f * i / steps
        }, total * i / steps)
    }

    // ---- MESSAGE ---------------------------------------------------------------------------
    private fun startMessage() {
        phase = Phase.MESSAGE
        stopVibration()
        ringControls.animate().alpha(0f).setDuration(250).withEndAction { ringControls.visibility = View.GONE }.start()
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
            followFile(Uri.fromFile(msg.file(this)), Phase.MESSAGE)
        } else {
            p.stop()
            startBrief()
        }
    }

    /**
     * Drive the orb from the file's own loudness, in sync with playback. Needs no permission.
     * Falls back to the Visualizer (needs mic permission) and then to a gentle pulse.
     */
    private fun followFile(uri: Uri, during: Phase) {
        visualizer?.release(); visualizer = null
        val env = Envelope.stream(this, uri)
        val r = object : Runnable {
            override fun run() {
                if (phase != during) return
                val p = player ?: return
                val v = env.at(p.currentPosition + 40)
                when {
                    !p.isPlaying -> orb.setLevel(0f)
                    v >= 0f -> orb.setLevel(v)
                    env.finished && env.available == 0 -> { attachVisualizer(p.audioSessionId); return }  // couldn't decode
                    else -> orb.setLevel(0.25f)  // decoder hasn't reached this point yet
                }
                ui.postDelayed(this, 16)
            }
        }
        ui.post(r)
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
                        orb.setLevel(Math.pow(norm, 0.6).toFloat())
                    }
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) {}
                }, Visualizer.getMaxCaptureRate(), true, false)
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
        val text = if (alarm.brief) Brief.compose(this, alarm = alarm) else ""
        if (text.isBlank()) { enterDone(); return }
        sub.text = dayLine()
        reveal = SentenceReveal(briefView, text.replace("Phone down.", "").trim())
        briefView.visibility = View.VISIBLE

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
        followFile(Uri.fromFile(f), Phase.BRIEF)
        // reveal each sentence when playback reaches its share of the audio (by length)
        val follow = object : Runnable {
            override fun run() {
                if (phase != Phase.BRIEF) return
                val dur = p.duration
                if (dur > 0) reveal?.showUpTo(p.currentPosition.toFloat() / dur)
                ui.postDelayed(this, 100)
            }
        }
        ui.post(follow)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED && phase == Phase.BRIEF) { p.removeListener(this); enterDone() } }
            override fun onPlayerError(e: PlaybackException) { if (phase == Phase.BRIEF) { p.removeListener(this); speakWithPhone(Brief.compose(this@AlarmActivity, alarm = alarm)) } }
        })
    }

    private fun speakWithPhone(text: String) {
        // the phone voice gives us no audio to measure, but it reports each word as it starts:
        // kick the orb on every word and let it fall back in between
        val drop = Runnable { orb.setLevel(0.12f) }
        speaker = BriefSpeaker(this,
            onSentence = { i -> runOnUiThread { reveal?.showIndex(i) } },
            onWord = { runOnUiThread { orb.setLevel(0.75f + 0.25f * Math.random().toFloat()); ui.removeCallbacks(drop); ui.postDelayed(drop, 150) } }
        ) { runOnUiThread { enterDone() } }.also { it.speak(text) }
    }

    // ---- DONE ------------------------------------------------------------------------------
    /** "Saturday · Work": the day, plus the alarm's label in the accent colour. */
    private fun dayLine(): CharSequence {
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
        val l = alarm.label.trim()
        if (l.isEmpty()) return day
        val sp = android.text.SpannableString("$day · $l")
        sp.setSpan(android.text.style.ForegroundColorSpan(0xFF93AEFF.toInt()), day.length + 3, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }
    private var reveal: SentenceReveal? = null

    /** Everything has been said: settle, then fade the screen out on its own. */
    private fun enterDone() {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        reveal?.showAll()
        orb.mode = OrbView.Mode.STILL
        if (!preview && alarm.note.isBlank() && Brief.datePlan(this, System.currentTimeMillis()).isBlank()) Brief.clearIntention(this)  // the evening plan was used up; per-alarm messages stay
        val hold = if (reveal != null) 4_000L else 1_500L
        ui.postDelayed({
            findViewById<View>(R.id.alarm_root).animate().alpha(0f).setDuration(900)
                .withEndAction { finishAlarm(!preview) }.start()
        }, hold)
    }

    private fun finishAlarm(realDismiss: Boolean) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(AlarmReceiver.NOTIFICATION_ID)
        if (!preview && !timerMode && realDismiss) {
            try { Mornings.recordWake(this, AlarmScheduler.snoozesLeft(this) == AlarmScheduler.MAX_SNOOZES) } catch (_: Throwable) {}
            AlarmScheduler.onDismissed(this, alarm.id)
        }
        finishAndRemoveTask()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        stopVibration()
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

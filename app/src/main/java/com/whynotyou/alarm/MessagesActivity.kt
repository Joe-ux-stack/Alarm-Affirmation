package com.whynotyou.alarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

class MessagesActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var recorderBox: View
    private lateinit var recTime: TextView
    private lateinit var recFrom: EditText
    private lateinit var recOrb: OrbView

    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private val ui = Handler(Looper.getMainLooper())
    private var preview: MediaPlayer? = null

    private val pickImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        askFrom { from ->
            val m = MessageStore.import(this, uri, from)
            Toast.makeText(this, if (m != null) "Added" else "Couldn't read that file", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRecording() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)
        list = findViewById(R.id.list); recorderBox = findViewById(R.id.recorder)
        recTime = findViewById(R.id.rec_time); recFrom = findViewById(R.id.rec_from); recOrb = findViewById(R.id.rec_orb)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_record).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
            else askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        findViewById<Button>(R.id.btn_import).setOnClickListener { pickImport.launch(arrayOf("audio/*", "video/*")) }
        findViewById<Button>(R.id.rec_cancel).setOnClickListener { stopRecording(save = false) }
        findViewById<Button>(R.id.rec_stop).setOnClickListener { stopRecording(save = true) }

        findViewById<View>(R.id.row_permissions).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        findViewById<View>(R.id.row_test).setOnClickListener {
            AlarmScheduler.scheduleTestInMinutes(this, 1)
            Toast.makeText(this, "Lock the phone. Firing in 60 s.", Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.row_voices).setOnClickListener {
            val v = ModelStore.selected(this)
            if (ModelStore.isDownloaded(this, v)) {
                android.app.AlertDialog.Builder(this, R.style.SheetDark).setTitle("Voices downloaded")
                    .setMessage("${v.label} quality, ${v.mb} MB on this phone.")
                    .setPositiveButton("Keep", null)
                    .setNegativeButton("Delete") { _, _ -> ModelStore.delete(this, v); renderVoiceRows() }.show()
            } else if (!ModelStore.isDownloading()) {
                ModelStore.download(this, v) { ok -> runOnUiThread { renderVoiceRows(); if (ok) VoiceWarmup.start(this) else Toast.makeText(this, "Download failed: ${ModelStore.error}", Toast.LENGTH_LONG).show() } }
                renderVoiceRows()
            }
        }
        findViewById<View>(R.id.row_quality).setOnClickListener {
            val names = ModelStore.variants.map { "${it.label} · ${it.mb} MB" }.toTypedArray()
            val cur = ModelStore.variants.indexOf(ModelStore.selected(this))
            android.app.AlertDialog.Builder(this, R.style.SheetDark).setTitle("Voice quality")
                .setSingleChoiceItems(names, cur) { d, i -> ModelStore.select(this, ModelStore.variants[i].id); renderVoiceRows(); d.dismiss() }.show()
        }
        findViewById<View>(R.id.row_voicetest).setOnClickListener {
            VoiceEngine.clearProbe(this); VoiceEngine.unmarkBroken(this)
            val probe = findViewById<TextView>(R.id.txt_probe)
            probe.visibility = View.VISIBLE; probe.text = "running…"
            Thread {
                val f = File(cacheDir, "voicetest.wav")
                val ok = VoiceEngine.synthesizeToFile(this, "Good morning.", VoiceEngine.DEFAULT_VOICE, f)
                runOnUiThread {
                    probe.text = (VoiceEngine.probeLog(this) ?: "") + "\nresult=$ok err=${VoiceEngine.lastError}"
                    if (ok) MediaPlayer().apply { setDataSource(f.absolutePath); setOnCompletionListener { release() }; prepare(); start() }
                }
            }.start()
        }
        VoiceEngine.probeLog(this)?.let { findViewById<TextView>(R.id.txt_probe).apply { visibility = View.VISIBLE; text = it } }

        findViewById<View>(R.id.row_preview).setOnClickListener {
            startActivity(Intent(this, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_PREVIEW, true))
        }
    }

    override fun onResume() { super.onResume(); refresh(); renderVoiceRows() }

    private val voiceTick = object : Runnable { override fun run() { renderVoiceRows() } }
    private fun renderVoiceRows() {
        val v = ModelStore.selected(this)
        findViewById<TextView>(R.id.txt_quality).text = v.label
        val t = findViewById<TextView>(R.id.txt_voices)
        t.removeCallbacks(voiceTick)
        t.text = when {
            ModelStore.isDownloading() -> { t.postDelayed(voiceTick, 500); "Downloading ${ModelStore.progress}%" }
            ModelStore.isDownloaded(this, v) -> "Downloaded"
            else -> "Download · ${v.mb} MB"
        }
    }
    override fun onPause() { super.onPause(); stopPreview(); if (recorder != null) stopRecording(save = true) }

    private fun refresh() {
        list.removeAllViews()
        val msgs = MessageStore.all(this)
        val next = MessageStore.peekNext(this)
        for (m in msgs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(14))
            }
            val play = ImageButton(this).apply {
                setImageResource(R.drawable.ic_play); setColorFilter(0xFFF2F4F8.toInt())
                setBackgroundResource(R.drawable.bg_circle_outline)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) }
                contentDescription = "Play"
                setOnClickListener { playPreview(m, this) }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = if (m.from.isNotBlank()) m.from else m.name; textSize = 17f; setTextColor(0xFFF2F4F8.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            col.addView(TextView(this).apply {
                text = "%d:%02d".format(m.durationMs / 60000, (m.durationMs / 1000) % 60) + (if (m == next && msgs.size > 1) " · next" else "")
                textSize = 13f; setTextColor(0xFF7A8294.toInt())
            })
            val del = TextView(this).apply {
                text = "Remove"; textSize = 13f; setTextColor(0xFF555B68.toInt()); minHeight = dp(44); gravity = Gravity.CENTER
                setPadding(dp(12), 0, 0, 0)
                setOnClickListener { MessageStore.remove(this@MessagesActivity, m.id); refresh() }
            }
            row.addView(play); row.addView(col); row.addView(del)
            list.addView(row)
            list.addView(View(this).apply {
                setBackgroundColor(0xFF15181F.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
        findViewById<TextView>(R.id.txt_permissions).text = if (Permissions.allGranted(this)) "All set ✓" else "${Permissions.missingCount(this)} missing"
    }

    // ---- preview playback ------------------------------------------------------------------
    private fun playPreview(m: Message, btn: ImageButton) {
        if (preview != null) { stopPreview(); return }
        preview = MediaPlayer().apply {
            setDataSource(m.file(this@MessagesActivity).absolutePath)
            setOnCompletionListener { stopPreview() }
            prepare(); start()
        }
        btn.setImageResource(R.drawable.ic_stop)
    }
    private fun stopPreview() {
        preview?.release(); preview = null
        refresh()
    }

    // ---- recording -------------------------------------------------------------------------
    private fun startRecording() {
        stopPreview()
        val f = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        try {
            recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000); setAudioSamplingRate(44_100)
                setOutputFile(f.absolutePath)
                prepare(); start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start the microphone", Toast.LENGTH_SHORT).show(); return
        }
        recFile = f; recStart = System.currentTimeMillis()
        recorderBox.visibility = View.VISIBLE
        findViewById<View>(R.id.btn_record).visibility = View.GONE
        findViewById<View>(R.id.btn_import).visibility = View.GONE
        recOrb.mode = OrbView.Mode.SPEAK
        ui.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val s = (System.currentTimeMillis() - recStart) / 1000
            recTime.text = "%d:%02d".format(s / 60, s % 60)
            val amp = try { r.maxAmplitude / 32767f } catch (e: Exception) { 0f }
            recOrb.setLevel(amp * 2.5f)
            if (s >= 120) { stopRecording(save = true); return }
            ui.postDelayed(this, 100)
        }
    }

    private fun stopRecording(save: Boolean) {
        ui.removeCallbacks(tick)
        try { recorder?.stop() } catch (e: Exception) { }
        recorder?.release(); recorder = null
        recorderBox.visibility = View.GONE
        findViewById<View>(R.id.btn_record).visibility = View.VISIBLE
        findViewById<View>(R.id.btn_import).visibility = View.VISIBLE
        val f = recFile ?: return
        val longEnough = System.currentTimeMillis() - recStart > 1500
        if (save && longEnough) {
            MessageStore.import(this, android.net.Uri.fromFile(f), recFrom.text.toString().trim())
            recFrom.setText("")
        }
        f.delete(); recFile = null
        refresh()
    }

    private fun askFrom(then: (String) -> Unit) {
        val input = EditText(this).apply { hint = "From"; setPadding(dp(24), dp(16), dp(24), dp(16)) }
        AlertDialog.Builder(this, R.style.DialogDark)
            .setView(input)
            .setPositiveButton("Add") { _, _ -> then(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

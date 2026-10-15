package com.whynotyou.alarm

import android.content.Context
import java.io.File

/**
 * Loads the voice engine and renders the 7 picker previews in the background when the app opens,
 * so the first tap on a voice plays immediately instead of after a 10-20 s load.
 */
object VoiceWarmup {
    const val SAMPLE = "Good morning. It's Thursday. Standup at ten, lunch at twelve thirty. You've handled harder."
    @Volatile var loaded = false
    @Volatile var status = "idle"
    private var started = false

    fun previewFile(c: Context, voiceId: String) = File(c.cacheDir, "preview_v2_${voiceId}.wav")

    @Synchronized fun start(c: Context) {
        if (started || !VoiceEngine.isAvailable(c) || VoiceEngine.isBroken(c)) return
        started = true
        val app = c.applicationContext
        Thread({
            try {
                status = "loading engine"
                Kokoro.ensureLoaded(app)
                loaded = true
                for (v in VoiceEngine.voices) {
                    val f = previewFile(app, v.id)
                    if (f.exists() && f.length() > 1000) continue
                    status = "rendering ${v.name}"
                    val tmp = File(app.cacheDir, "preview_tmp_${v.id}.wav")
                    if (VoiceEngine.synthesizeToFile(app, SAMPLE, v.id, tmp)) tmp.renameTo(f) else tmp.delete()
                }
                status = "ready"
            } catch (e: Throwable) {
                status = "failed: $e"; VoiceEngine.lastError = e.toString()
            }
        }, "voice-warmup").apply { priority = Thread.MIN_PRIORITY + 1; isDaemon = true }.start()
    }
}

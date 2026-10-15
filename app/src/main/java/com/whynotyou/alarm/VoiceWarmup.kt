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

    /** Bundled tester clip (same text for every voice), copied out of assets on first use. */
    fun previewFile(c: Context, voiceId: String): File {
        val f = File(c.cacheDir, "tester_$voiceId.m4a")
        if (!f.exists()) try { c.assets.open("kokoro/preview/$voiceId.m4a").use { i -> f.outputStream().use { o -> i.copyTo(o) } } } catch (_: Exception) {}
        return f
    }

    @Synchronized fun start(c: Context) {
        if (started || !VoiceEngine.isAvailable(c) || VoiceEngine.isBroken(c)) return
        started = true
        val app = c.applicationContext
        Thread({
            try {
                status = "loading engine"
                Kokoro.ensureLoaded(app)
                loaded = true
                status = "ready"
            } catch (e: Throwable) {
                status = "failed: $e"; VoiceEngine.lastError = e.toString()
            }
        }, "voice-warmup").apply { priority = Thread.MIN_PRIORITY + 1; isDaemon = true }.start()
    }
}

package com.whynotyou.alarm

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * On-device Kokoro TTS via sherpa-onnx. Model + 7 voices live in assets/kokoro and are copied to
 * filesDir on first use (sherpa needs real file paths). Output: 24 kHz 16-bit mono WAV.
 */
object VoiceEngine {

    // sid = index in the untouched 54-voice table (alphabetical in the sherpa package)
    data class Voice(val id: String, val name: String, val region: String, val sex: String, val sid: Int)
    val voices = listOf(
        Voice("af_sky", "Sky", "US", "F", 10),
        Voice("af_heart", "Heart", "US", "F", 3),
        Voice("af_alloy", "Alloy", "US", "F", 0),
        Voice("af_nova", "Nova", "US", "F", 7),
        Voice("bf_emma", "Emma", "UK", "F", 21),
        Voice("bm_fable", "Fable", "UK", "M", 25),
        Voice("bm_lewis", "Lewis", "UK", "M", 27),
    )
    const val DEFAULT_VOICE = "af_sky"
    fun voice(id: String) = voices.firstOrNull { it.id == id } ?: voices[0]

    @Volatile private var tts: Any? = null
    private val lock = Any()

    /** Written step by step so a native crash still leaves a trail. */
    private fun log(c: Context, step: String) {
        try { File(c.filesDir, "voice_probe.txt").appendText("${System.currentTimeMillis() % 100000} $step\n") } catch (_: Throwable) {}
    }
    fun probeLog(c: Context): String? = File(c.filesDir, "voice_probe.txt").takeIf { it.exists() }?.readText()
    fun clearProbe(c: Context) { File(c.filesDir, "voice_probe.txt").delete() }

    /** Set once the engine failed to open; everything then falls back to the phone voice. */
    fun isBroken(c: Context) = File(c.filesDir, "voice_broken").exists()
    private fun markBroken(c: Context, why: String) { File(c.filesDir, "voice_broken").writeText(why) }
    fun unmarkBroken(c: Context) { File(c.filesDir, "voice_broken").delete() }

    /** Kill switch while the native engine is being replaced. */
    const val ENABLED = false
    fun isAvailable(c: Context): Boolean = ENABLED && try { c.assets.list("kokoro")?.contains("model.int8.onnx") == true } catch (e: Exception) { false }

    @Volatile var lastError: String? = null

    /** Everything is copied out of assets to real files once (sherpa + espeak are happiest that way). */
    fun isReady(c: Context) = File(c.filesDir, "kokoro/.ready-v2").exists()

    fun prepare(c: Context): File {
        val dir = File(c.filesDir, "kokoro")
        val marker = File(dir, ".ready-v2")
        if (marker.exists()) return dir
        dir.deleteRecursively(); dir.mkdirs()
        copyAssetDir(c, "kokoro", dir)
        marker.writeText("ok")
        return dir
    }

    private fun copyAssetDir(c: Context, path: String, dest: File) {
        val items = c.assets.list(path) ?: return
        if (items.isEmpty()) {
            c.assets.open(path).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
            return
        }
        dest.mkdirs()
        for (it in items) copyAssetDir(c, "$path/$it", File(dest, it))
    }

    private fun engine(c: Context): Nothing {
        log(c, "engine: disabled in this build")
        throw IllegalStateException("voice engine disabled")
    }

    /** Synthesise to a WAV file. Blocking; call off the main thread. Returns false on failure. */
    fun synthesizeToFile(c: Context, text: String, voiceId: String, out: File, speed: Float = 0.95f): Boolean {
        if (!ENABLED || text.isBlank() || isBroken(c)) return false
        return try {
            engine(c)
        } catch (e: Throwable) {
            lastError = e.toString()
            out.delete(); false
        }
    }

}

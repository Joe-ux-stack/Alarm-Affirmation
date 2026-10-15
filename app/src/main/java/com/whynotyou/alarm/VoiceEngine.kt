package com.whynotyou.alarm

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * On-device Kokoro TTS via ONNX Runtime (see Kokoro.kt). Output: 24 kHz 16-bit mono WAV.
 */
object VoiceEngine {

    // sid = index in our trimmed voices.bin (order of tools/prepare_kokoro.py KEEP)
    data class Voice(val id: String, val name: String, val region: String, val sex: String, val sid: Int)
    val voices = listOf(
        Voice("af_sky", "Sky", "US", "F", 0),
        Voice("af_heart", "Heart", "US", "F", 1),
        Voice("af_alloy", "Alloy", "US", "F", 2),
        Voice("af_nova", "Nova", "US", "F", 3),
        Voice("bf_emma", "Emma", "UK", "F", 4),
        Voice("bm_fable", "Fable", "UK", "M", 5),
        Voice("bm_lewis", "Lewis", "UK", "M", 6),
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

    const val ENABLED = true
    fun isAvailable(c: Context): Boolean = ENABLED && try { c.assets.list("kokoro")?.contains("model.int8.onnx") == true } catch (e: Exception) { false }

    @Volatile var lastError: String? = null

    /** Synthesise to a WAV file. Blocking; call off the main thread. Returns false on failure. */
    fun synthesizeToFile(c: Context, text: String, voiceId: String, out: File, speed: Float = 1.0f): Boolean {
        if (!ENABLED || text.isBlank() || isBroken(c)) return false
        return try {
            log(c, "synth: ${text.take(40)} voice=$voiceId")
            Kokoro.synthesizeToWav(c, text, voiceId, out, speed)
            log(c, "synth: ok ${out.length()} bytes")
            out.length() > 44
        } catch (e: Throwable) {
            log(c, "synth: FAILED $e")
            lastError = e.toString()
            out.delete(); false
        }
    }

}

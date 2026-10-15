package com.whynotyou.alarm

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.io.FileOutputStream

/**
 * On-device Kokoro TTS via sherpa-onnx. Model + 7 voices live in assets/kokoro and are copied to
 * filesDir on first use (sherpa needs real file paths). Output: 24 kHz 16-bit mono WAV.
 */
object VoiceEngine {

    data class Voice(val id: String, val name: String, val region: String, val sex: String)

    val voices = listOf(
        Voice("af_sky", "Sky", "US", "F"),
        Voice("af_heart", "Heart", "US", "F"),
        Voice("af_alloy", "Alloy", "US", "F"),
        Voice("af_nova", "Nova", "US", "F"),
        Voice("bf_emma", "Emma", "UK", "F"),
        Voice("bm_fable", "Fable", "UK", "M"),
        Voice("bm_lewis", "Lewis", "UK", "M"),
    )
    const val DEFAULT_VOICE = "af_sky"
    fun voice(id: String) = voices.firstOrNull { it.id == id } ?: voices[0]

    @Volatile private var tts: OfflineTts? = null
    private val lock = Any()

    fun isAvailable(c: Context): Boolean = try { c.assets.list("kokoro")?.contains("model.int8.onnx") == true } catch (e: Exception) { false }

    @Volatile var lastError: String? = null

    /** espeak-ng needs real files, so that folder is copied out of assets once; the model is read from assets directly. */
    private fun espeakDir(c: Context): File {
        val dir = File(c.filesDir, "espeak-ng-data")
        val marker = File(dir, ".ready-v1")
        if (marker.exists()) return dir
        dir.mkdirs()
        copyAssetDir(c, "kokoro/espeak-ng-data", dir)
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

    private fun engine(c: Context): OfflineTts {
        tts?.let { return it }
        synchronized(lock) {
            tts?.let { return it }
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = "kokoro/model.int8.onnx",
                        voices = "kokoro/voices.bin",
                        tokens = "kokoro/tokens.txt",
                        dataDir = espeakDir(c).absolutePath,
                        lexicon = "kokoro/lexicon-us-en.txt",
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            return OfflineTts(assetManager = c.assets, config = cfg).also { tts = it }
        }
    }

    /** Synthesise to a WAV file. Blocking; call off the main thread. Returns false on failure. */
    fun synthesizeToFile(c: Context, text: String, voiceId: String, out: File, speed: Float = 0.95f): Boolean {
        if (text.isBlank()) return false
        return try {
            val e = engine(c)
            val sid = voices.indexOfFirst { it.id == voiceId }.coerceAtLeast(0)
            val audio = e.generate(text = text, sid = sid, speed = speed)
            if (audio.samples.isEmpty()) return false
            audio.save(out.absolutePath)
        } catch (e: Throwable) {
            lastError = e.toString()
            out.delete(); false
        }
    }

}

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

    @Volatile private var tts: OfflineTts? = null
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

    fun isAvailable(c: Context): Boolean = try { c.assets.list("kokoro")?.contains("model.int8.onnx") == true } catch (e: Exception) { false }

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

    private fun engine(c: Context): OfflineTts {
        tts?.let { return it }
        synchronized(lock) {
            tts?.let { return it }
            log(c, "engine: start")
            val dir = prepare(c)
            log(c, "engine: files ready " + listOf("model.int8.onnx","voices.bin","tokens.txt","lexicon-us-en.txt").joinToString { f -> "$f=" + File(dir, f).length() } + " espeak=" + File(dir, "espeak-ng-data").list()?.size)
            try { System.loadLibrary("sherpa-onnx-jni"); log(c, "engine: jni loaded") } catch (e: Throwable) { log(c, "engine: jni load FAILED $e"); markBroken(c, "jni: $e"); throw e }
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                        lexicon = File(dir, "lexicon-us-en.txt").absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            log(c, "engine: constructing OfflineTts")
            val e = try { OfflineTts(assetManager = c.assets, config = cfg) } catch (t: Throwable) { log(c, "engine: ctor FAILED $t"); markBroken(c, "ctor: $t"); throw t }
            log(c, "engine: ok speakers=${e.numSpeakers()} sr=${e.sampleRate()}")
            return e.also { tts = it }
        }
    }

    /** Synthesise to a WAV file. Blocking; call off the main thread. Returns false on failure. */
    fun synthesizeToFile(c: Context, text: String, voiceId: String, out: File, speed: Float = 0.95f): Boolean {
        if (text.isBlank() || isBroken(c)) return false
        return try {
            val e = engine(c)
            val sid = voice(voiceId).sid
            log(c, "gen: sid=$sid chars=${text.length}")
            val audio = e.generate(text = text, sid = sid, speed = speed)
            log(c, "gen: samples=${audio.samples.size}")
            if (audio.samples.isEmpty()) return false
            val ok = audio.save(out.absolutePath)
            log(c, "gen: saved=$ok bytes=${out.length()}")
            ok
        } catch (e: Throwable) {
            lastError = e.toString()
            out.delete(); false
        }
    }

}

package com.whynotyou.alarm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Kokoro-82M on-device via ONNX Runtime, with our own English G2P (lexicon lookup + letter-to-sound
 * fallback). No espeak, no sherpa. Assets: kokoro/model.int8.onnx, voices.bin (7 x 510 x 256 f32),
 * tokens.txt, lexicon.txt. Output 24 kHz mono.
 */
object Kokoro {
    private const val SR = 24000
    private const val MAX_TOKENS = 508

    private val lock = Any()
    @Volatile private var session: OrtSession? = null
    private var vocab: HashMap<Int, Long>? = null          // codepoint -> token id
    private var lexicon: HashMap<String, String>? = null   // word -> phoneme string
    private var voices: FloatArray? = null                  // [7][510][256]
    private var voiceNames: List<String> = emptyList()

    private fun log(c: Context, s: String) {
        try { File(c.filesDir, "voice_probe.txt").appendText("${System.currentTimeMillis() % 100000} $s\n") } catch (_: Throwable) {}
    }

    /** The downloaded model for the selected quality (see ModelStore). */
    private fun modelFile(c: Context): File {
        val f = ModelStore.file(c)
        if (!ModelStore.isDownloaded(c)) throw IllegalStateException("model not downloaded")
        return f
    }

    fun unload() { synchronized(lock) { try { session?.close() } catch (_: Throwable) {}; session = null } }

    fun ensureLoaded(c: Context) {
        if (session != null) return
        synchronized(lock) {
            if (session != null) return
            log(c, "kokoro: loading")
            val vb = HashMap<Int, Long>()
            c.assets.open("kokoro/tokens.txt").bufferedReader().forEachLine { line ->
                val i = line.lastIndexOf(' '); if (i <= 0) return@forEachLine
                val sym = line.substring(0, i); val id = line.substring(i + 1).toLongOrNull() ?: return@forEachLine
                vb[sym.codePointAt(0)] = id
            }
            vocab = vb
            val lx = HashMap<String, String>(200_000)
            c.assets.open("kokoro/lexicon.txt").bufferedReader().forEachLine { line ->
                val i = line.indexOf(' '); if (i <= 0) return@forEachLine
                lx[line.substring(0, i)] = line.substring(i + 1).replace(" ", "")
            }
            lexicon = lx
            voiceNames = c.assets.open("kokoro/voices.txt").bufferedReader().readLines().filter { it.isNotBlank() }
            val vBytes = c.assets.open("kokoro/voices.bin").readBytes()
            val fb = ByteBuffer.wrap(vBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            voices = FloatArray(fb.remaining()).also { fb.get(it) }
            log(c, "kokoro: lexicon=${lx.size} voices=${voiceNames.size}")
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(modelFile(c).absolutePath, opts)
            log(c, "kokoro: session ready")
        }
    }

    // ---- text normalisation --------------------------------------------------------------
    private val ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split(" ")
    private val TENS = "x x twenty thirty forty fifty sixty seventy eighty ninety".split(" ")
    private fun num(n: Int): String = when {
        n < 0 -> "minus " + num(-n)
        n < 20 -> ONES[n]
        n < 100 -> TENS[n / 10] + if (n % 10 == 0) "" else " " + ONES[n % 10]
        n < 1000 -> ONES[n / 100] + " hundred" + if (n % 100 == 0) "" else " " + num(n % 100)
        n < 10000 -> num(n / 1000) + " thousand" + if (n % 1000 == 0) "" else " " + num(n % 1000)
        else -> n.toString().map { num(it - '0') }.joinToString(" ")
    }
    private fun expand(text: String): String {
        var t = Regex("(\\d{1,2}):(\\d{2})").replace(text) { m ->
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            when { mi == 0 -> num(h) + " o'clock"; mi < 10 -> num(h) + " oh " + num(mi); else -> num(h) + " " + num(mi) }
        }
        t = Regex("\\d+").replace(t) { m -> num(m.value.take(9).toInt()) }
        return t.replace("&", " and ").replace("@", " at ").replace("%", " percent").replace("+", " plus ")
    }

    // ---- G2P ----------------------------------------------------------------------------
    private val RULES = listOf(
        "tion" to "ʃən", "ough" to "ʌf", "igh" to "ˈaɪ", "ch" to "ʧ", "sh" to "ʃ", "th" to "θ", "ph" to "f", "wh" to "w",
        "ck" to "k", "ng" to "ŋ", "qu" to "kw", "ee" to "i", "ea" to "i", "oo" to "u", "ou" to "aʊ", "ow" to "oʊ",
        "ai" to "eɪ", "ay" to "eɪ", "oi" to "ɔɪ", "oy" to "ɔɪ", "ar" to "ɑɹ", "er" to "ɚ", "or" to "ɔɹ", "ur" to "ɚ", "ir" to "ɚ",
        "a" to "æ", "b" to "b", "c" to "k", "d" to "d", "e" to "ɛ", "f" to "f", "g" to "ɡ", "h" to "h", "i" to "ɪ", "j" to "ʤ",
        "k" to "k", "l" to "l", "m" to "m", "n" to "n", "o" to "ɑ", "p" to "p", "r" to "ɹ", "s" to "s", "t" to "t", "u" to "ʌ",
        "v" to "v", "w" to "w", "x" to "ks", "y" to "i", "z" to "z"
    )
    private fun lts(w: String): String {
        val sb = StringBuilder(); var i = 0
        outer@ while (i < w.length) {
            for ((k, v) in RULES) if (w.startsWith(k, i)) { sb.append(v); i += k.length; continue@outer }
            i++
        }
        return sb.toString()
    }
    private fun wordPhonemes(word: String): String {
        val lx = lexicon ?: return lts(word)
        val w = word.lowercase().trim('\'')
        if (w.isEmpty()) return ""
        lx[w]?.let { return it }
        if (w.endsWith("'s")) lx[w.dropLast(2)]?.let { return it + "z" }
        if (w.endsWith("s") && w.length > 2) lx[w.dropLast(1)]?.let { return it + "z" }
        if (w.endsWith("ing") && w.length > 4) lx[w.dropLast(3)]?.let { return it + "ɪŋ" }
        if (w.endsWith("ed") && w.length > 3) lx[w.dropLast(2)]?.let { return it + "d" }
        if (w.contains("-")) return w.split("-").joinToString(" ") { wordPhonemes(it) }
        return lts(w)
    }

    /** Text -> token ids (without the leading/trailing pad). */
    fun tokenize(text: String): LongArray {
        val vb = vocab ?: return LongArray(0)
        val t = expand(text)
        val out = ArrayList<Long>()
        val re = Regex("[A-Za-z'\\-]+|[.,!?;:]|\\s+|.")
        for (m in re.findAll(t)) {
            val s = m.value
            val ph: String = when {
                s.isBlank() -> " "
                s[0].isLetter() -> wordPhonemes(s)
                else -> s
            }
            var i = 0
            while (i < ph.length) { val cp = ph.codePointAt(i); vb[cp]?.let { out.add(it) }; i += Character.charCount(cp) }
        }
        // collapse doubled spaces
        val res = ArrayList<Long>(out.size); val space = vb[' '.code]
        for (id in out) { if (id == space && res.lastOrNull() == space) continue; res.add(id) }
        while (res.firstOrNull() == space) res.removeAt(0)
        while (res.lastOrNull() == space) res.removeAt(res.size - 1)
        return res.toLongArray()
    }

    // ---- synthesis ----------------------------------------------------------------------
    private fun synthOne(ids: LongArray, voiceIdx: Int, speed: Float): FloatArray {
        val s = session ?: throw IllegalStateException("not loaded")
        val v = voices ?: throw IllegalStateException("no voices")
        val n = ids.size.coerceAtMost(MAX_TOKENS)
        val toks = LongArray(n + 2); System.arraycopy(ids, 0, toks, 1, n)
        val style = FloatArray(256); val base = (voiceIdx * 510 + n) * 256
        System.arraycopy(v, base, style, 0, 256)
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, LongBuffer.wrap(toks), longArrayOf(1, (n + 2).toLong())).use { tT ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, 256)).use { sT ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1)).use { spT ->
                    s.run(mapOf("tokens" to tT, "style" to sT, "speed" to spT)).use { r ->
                        val audio = r[0].value
                        return when (audio) {
                            is FloatArray -> audio
                            is Array<*> -> (audio[0] as FloatArray)
                            else -> FloatArray(0)
                        }
                    }
                }
            }
        }
    }

    private fun sentences(text: String): List<String> =
        Regex("(?<=[.!?])\\s+").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }

    /** Blocking. Writes a 24 kHz 16-bit mono WAV. */
    fun synthesizeToWav(c: Context, text: String, voiceId: String, out: File, speed: Float = 1.0f) {
        ensureLoaded(c)
        val vi = voiceNames.indexOf(voiceId).let { if (it < 0) 0 else it }
        val pcm = ArrayList<FloatArray>()
        val gap = FloatArray((SR * 0.35).toInt())
        for (s in sentences(text)) {
            val ids = tokenize(s); if (ids.isEmpty()) continue
            pcm.add(synthOne(ids, vi, speed)); pcm.add(gap)
        }
        val total = pcm.sumOf { it.size }
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + total * 2).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SR).putInt(SR * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(total * 2)
            f.write(h.array())
            val buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
            for (chunk in pcm) for (x in chunk) {
                val v = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                buf.putShort(v)
                if (!buf.hasRemaining()) { f.write(buf.array(), 0, buf.position()); buf.clear() }
            }
            f.write(buf.array(), 0, buf.position())
        }
    }
}

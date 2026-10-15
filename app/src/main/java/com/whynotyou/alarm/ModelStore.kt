package com.whynotyou.alarm

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Kokoro model is not in the APK; it is downloaded once (Wi-Fi recommended) from the project's
 * GitHub "models" release and kept in filesDir. Two qualities; voices/lexicon/testers ship in the APK.
 */
object ModelStore {
    data class Variant(val id: String, val label: String, val file: String, val mb: Int)
    val variants = listOf(
        Variant("standard", "Standard", "kokoro-int8.onnx", 114),
        Variant("high", "High", "kokoro-fp32.onnx", 326),
    )
    private const val BASE = "https://github.com/Joe-ux-stack/Alarm-Affirmation/releases/download/models/"
    private fun prefs(c: Context) = c.getSharedPreferences("models", Context.MODE_PRIVATE)

    fun selected(c: Context): Variant = variants.firstOrNull { it.id == prefs(c).getString("variant", "standard") } ?: variants[0]
    fun select(c: Context, id: String) { prefs(c).edit().putString("variant", id).apply(); Kokoro.unload() }

    fun file(c: Context, v: Variant = selected(c)) = File(c.filesDir, "model-${v.id}.onnx")
    private fun partFile(c: Context, v: Variant) = File(c.filesDir, "model-${v.id}.part")
    fun isDownloaded(c: Context, v: Variant = selected(c)) = file(c, v).let { it.exists() && it.length() > 1_000_000 }

    @Volatile var progress = -1        // 0..100 while downloading, -1 idle
    @Volatile var error: String? = null
    @Volatile private var thread: Thread? = null
    fun isDownloading() = thread?.isAlive == true

    /** Resumable download in a background thread. onDone(true) on success. */
    @Synchronized fun download(c: Context, v: Variant = selected(c), onDone: (Boolean) -> Unit = {}) {
        if (isDownloading() || isDownloaded(c, v)) { onDone(isDownloaded(c, v)); return }
        val app = c.applicationContext
        error = null; progress = 0
        thread = Thread({
            val part = partFile(app, v); val dest = file(app, v)
            try {
                val have = if (part.exists()) part.length() else 0L
                val conn = (URL(BASE + v.file).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                val resume = code == 206
                if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
                val total = (if (resume) have else 0L) + conn.contentLengthLong
                RandomAccessFile(part, "rw").use { out ->
                    if (resume) out.seek(have) else out.setLength(0)
                    val buf = ByteArray(1 shl 16); var got = if (resume) have else 0L
                    conn.inputStream.use { inp ->
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); got += n
                            if (total > 0) progress = ((got * 100) / total).toInt().coerceIn(0, 99)
                        }
                    }
                }
                if (!part.renameTo(dest)) throw IllegalStateException("rename failed")
                progress = 100
                onDone(true)
            } catch (e: Throwable) {
                error = e.toString(); progress = -1
                onDone(false)
            } finally { if (progress != 100) progress = -1 }
        }, "model-download").apply { isDaemon = true }
        thread?.start()
    }

    fun delete(c: Context, v: Variant) { file(c, v).delete(); partFile(c, v).delete(); Kokoro.unload() }
}

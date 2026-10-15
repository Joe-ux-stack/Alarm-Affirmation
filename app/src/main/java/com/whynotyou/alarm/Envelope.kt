package com.whynotyou.alarm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Loudness of an audio file, one value per 20 ms, normalised to 0..1. Decoded straight from the
 * file (no microphone permission needed), so the orb can follow exactly what is being played.
 */
object Envelope {
    const val FRAME_MS = 20

    fun of(c: Context, uri: Uri): FloatArray? = try {
        val ex = MediaExtractor(); ex.setDataSource(c, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: return null
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val frames = ArrayList<Float>()
        var acc = 0.0; var n = 0; var perFrame = rate * ch * FRAME_MS / 1000
        val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false
        val maxFrames = 15 * 60 * 1000 / FRAME_MS   // 15 min cap
        while (!outDone && frames.size < maxFrames) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(5_000)
                if (i >= 0) {
                    val sz = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (sz < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat; rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                perFrame = rate * ch * FRAME_MS / 1000
            } else if (o >= 0) {
                val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                while (sb.hasRemaining()) {
                    val s = sb.get() / 32768.0; acc += s * s; n++
                    if (n >= perFrame) { frames.add(sqrt(acc / n).toFloat()); acc = 0.0; n = 0 }
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        normalise(frames.toFloatArray())
    } catch (e: Throwable) { null }

    /** Scale so speech peaks reach ~1 and quiet passages stay low (95th percentile as the ceiling). */
    private fun normalise(a: FloatArray): FloatArray {
        if (a.isEmpty()) return a
        val sorted = a.sortedArray()
        val ceil = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)].coerceAtLeast(0.01f)
        val floor = sorted[(sorted.size * 0.10).toInt()]
        return FloatArray(a.size) { i -> (((a[i] - floor) / (ceil - floor)).coerceIn(0f, 1f)).let { Math.pow(it.toDouble(), 0.7).toFloat() } }
    }

    fun at(env: FloatArray, positionMs: Long): Float {
        val i = (positionMs / FRAME_MS).toInt()
        return if (i in env.indices) env[i] else 0f
    }
}

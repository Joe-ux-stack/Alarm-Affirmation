package com.whynotyou.alarm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Loudness of an audio file, one value per 20 ms, 0..1. Decoded straight from the file (no mic
 * permission). Streams: values become available as decoding runs, so the orb reacts from the
 * first second of a long song. Normalisation is causal (beats above the recent average, scaled by
 * a slowly-forgetting peak), so early values don't depend on the rest of the file.
 */
class Envelope private constructor() {
    @Volatile var available = 0; private set
    @Volatile var finished = false; private set
    private var data = FloatArray(4096)

    fun at(positionMs: Long): Float {
        val i = (positionMs / FRAME_MS).toInt()
        return if (i in 0 until available) data[i] else -1f
    }

    private fun push(v: Float) {
        if (available == data.size) data = data.copyOf(data.size * 2)
        data[available] = v; available++
    }

    companion object {
        const val FRAME_MS = 20

        /** Starts decoding on a background thread and returns immediately. */
        fun stream(c: Context, uri: Uri): Envelope {
            val env = Envelope()
            Thread({ try { decode(c, uri, env) } catch (_: Throwable) {} finally { env.finished = true } }, "envelope").start()
            return env
        }

        private fun decode(c: Context, uri: Uri, env: Envelope) {
            val ex = MediaExtractor(); ex.setDataSource(c, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var perFrame = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) * fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) * FRAME_MS / 1000
            var acc = 0.0; var n = 0
            var slow = -1f; var peak = 0.02f
            val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false
            val maxFrames = 15 * 60 * 1000 / FRAME_MS
            while (!outDone && env.available < maxFrames) {
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
                    val f = codec.outputFormat
                    perFrame = f.getInteger(MediaFormat.KEY_SAMPLE_RATE) * f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) * FRAME_MS / 1000
                } else if (o >= 0) {
                    val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (sb.hasRemaining()) {
                        val s = sb.get() / 32768.0; acc += s * s; n++
                        if (n >= perFrame) {
                            val rms = sqrt(acc / n).toFloat(); acc = 0.0; n = 0
                            if (slow < 0f) slow = rms
                            slow += (rms - slow) * 0.033f                      // ~0.6 s average
                            val beat = (rms - 0.7f * slow).coerceAtLeast(0f)   // what stands out right now
                            peak = maxOf(peak * 0.9985f, beat, 0.004f)         // forgets over ~15 s
                            env.push(Math.pow((beat / peak).coerceIn(0f, 1f).toDouble(), 0.8).toFloat())
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            codec.stop(); codec.release(); ex.release()
        }
    }
}

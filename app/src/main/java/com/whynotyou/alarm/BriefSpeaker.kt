package com.whynotyou.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Reads the morning brief aloud on the alarm stream. onDone fires when it finishes or fails. */
class BriefSpeaker(context: Context, private val onSentence: (Int) -> Unit = {}, private val onDone: () -> Unit) {

    private var tts: TextToSpeech? = null
    private var pending: String? = null
    private var finished = false
    @Volatile private var lastIndex = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) { done(); return@TextToSpeech }
            tts?.apply {
                language = Locale.getDefault()
                setSpeechRate(0.92f)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { id?.removePrefix("s")?.toIntOrNull()?.let(onSentence) }
                    override fun onDone(id: String?) { if (id == "s${lastIndex}") done() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { done() }
                })
            }
            pending?.let { speakNow(it) }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) { done(); return }
        pending = text
        if (tts != null && ttsReady()) speakNow(text)
    }

    private fun ttsReady() = try { tts?.voices != null } catch (e: Exception) { false }

    private fun speakNow(text: String) {
        pending = null
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_ALARM)
        }
        val parts = SentenceReveal.split(text)
        lastIndex = parts.size - 1
        for ((i, part) in parts.withIndex()) {
            val r = tts?.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "s$i")
            if (r != TextToSpeech.SUCCESS) { done(); return }
        }
    }

    private fun done() {
        if (finished) return
        finished = true
        onDone()
    }

    fun release() {
        finished = true
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}

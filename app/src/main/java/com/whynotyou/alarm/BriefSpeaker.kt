package com.whynotyou.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Reads the morning brief aloud on the alarm stream. onDone fires when it finishes or fails. */
class BriefSpeaker(context: Context, private val onDone: () -> Unit) {

    private var tts: TextToSpeech? = null
    private var pending: String? = null
    private var finished = false

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
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { done() }
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
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "brief")
        if (r != TextToSpeech.SUCCESS) done()
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

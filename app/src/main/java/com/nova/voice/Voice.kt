package com.nova.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.nova.storage.Prefs
import java.util.Locale

/**
 * Touch-to-talk speech capture with Android's SpeechRecognizer (spec §4.3). Prefers the
 * on-device recogniser when available (EXTRA_PREFER_OFFLINE); reports partial text, level,
 * final text and readable errors. Only listens while the user has asked it to.
 */
class VoiceInput(private val context: Context) {

    interface Callback {
        fun onReady()
        fun onLevel(rms: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String, code: Int)
    }

    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)
    fun hasPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(cb: Callback) {
        if (!available) { cb.onError("Speech recognition isn't available on this phone. Install/enable Google app voice typing.", -1); return }
        if (!hasPermission()) { cb.onError("Microphone permission needed", -2); return }
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; cb.onReady() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = cb.onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false; cb.onError(describe(error), error) }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (text.isBlank()) cb.onError("I didn't catch that", SpeechRecognizer.ERROR_NO_MATCH) else cb.onFinal(text)
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) cb.onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.voiceLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        runCatching { r.startListening(intent) }.onFailure { cb.onError("Couldn't start the microphone", -3) }
    }

    fun cancel() {
        listening = false
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
    }

    private fun describe(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Speech service needs internet (or download offline speech in Google settings)"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service busy — try again"
        else -> "Speech error ($code)"
    }
}

/** Optional spoken answers via Android TextToSpeech; checks the language is installed. */
class Speaker(context: Context) {
    private var ready = false
    var status = "Starting text-to-speech…"
        private set
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { s ->
        ready = s == TextToSpeech.SUCCESS
        status = if (ready) applyLanguage() else "Text-to-speech unavailable"
    }

    fun applyLanguage(): String {
        val loc = Locale.forLanguageTag(Prefs.voiceLanguage)
        return when (tts.setLanguage(loc)) {
            TextToSpeech.LANG_MISSING_DATA -> "Voice data for ${loc.displayName} not installed"
            TextToSpeech.LANG_NOT_SUPPORTED -> { tts.setLanguage(Locale.ENGLISH); "${loc.displayName} not supported — using English" }
            else -> "Ready (${loc.displayName})"
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank() || !Prefs.speakReplies) return
        tts.speak(text.take(300), TextToSpeech.QUEUE_FLUSH, null, "nova")
    }

    fun stop() { runCatching { tts.stop() } }
    fun shutdown() { runCatching { tts.shutdown() } }
}

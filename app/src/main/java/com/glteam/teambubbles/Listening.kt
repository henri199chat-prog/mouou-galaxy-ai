package com.glteam.teambubbles

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Pure rules of listening (spec §8.2), unit-tested off-device. */
object ListenRules {
    /** Tunisian Arabic first; if the phone's recognizer refuses it, plain Arabic. */
    const val PRIMARY_LANGUAGE = "ar-TN"
    const val FALLBACK_LANGUAGE = "ar"

    /** Hold longer than this and release = push-to-talk end (spec §6). */
    const val PUSH_TO_TALK_MS = 1200L

    /** Speech level for the listening waves: rmsdB (about −2 … 10) → 0 … 1. */
    fun level(rmsDb: Float): Float = ((rmsDb + 2f) / 12f).coerceIn(0f, 1f)

    /** Language errors switch to the fallback language once; other errors end listening. */
    fun shouldRetryWithFallback(error: Int, language: String): Boolean =
        language == PRIMARY_LANGUAGE && (error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE)

    /** Truthful Darija line for each recognizer error (spec §8.2 "truthful error mapping"). */
    fun darija(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ما سمعتكش مليح، عاود احكي أيمون"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "لازمني إذن الميكروفون: حلّ الإعدادات وأعطيه"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, ERROR_SERVER_DISCONNECTED ->
            "التعرّف على الصوت يلزمو إنترنت توا، ثبّت الكونيكسيون"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "الميكروفون مشغول بتطبيق آخر، عاود بعد شوية"
        SpeechRecognizer.ERROR_AUDIO -> "مشكل في الميكروفون، عاود"
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> "التعرّف على العربية موش متوفّر في التليفون"
        else -> "ما نجمتش نسمعك توا (خطأ $error)، عاود"
    }

    /** Keeps the newest non-blank text: a final result wins, otherwise the last partial. */
    fun best(final: String?, lastPartial: String?): String = final?.trim().orEmpty().ifEmpty { lastPartial?.trim().orEmpty() }

    // Constants added in later API levels (values from the Android SDK), so the rules compile and test anywhere.
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/**
 * Listening with Android's SpeechRecognizer (spec §8.2): ar-TN → ar, live partial text, speech level for
 * the listening face. Must be used on the main thread. One session at a time.
 */
class VoiceListener(private val context: Context, private val callbacks: Callbacks) {
    interface Callbacks {
        fun onPartial(text: String)
        fun onLevel(level: Float)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var language = ListenRules.PRIMARY_LANGUAGE
    private var lastPartial: String? = null
    var isListening = false
        private set

    fun available() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (isListening) return
        if (!available()) {
            callbacks.onError("ما فماش خدمة التعرّف على الصوت في التليفون (Google)")
            return
        }
        language = ListenRules.PRIMARY_LANGUAGE
        begin()
    }

    /** Push-to-talk end: stop and deliver what was heard so far. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        isListening = false
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun begin() {
        cancel()
        lastPartial = null
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = callbacks.onLevel(ListenRules.level(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    lastPartial = text
                    callbacks.onPartial(text)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = ListenRules.best(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(), lastPartial)
                finish()
                if (text.isEmpty()) callbacks.onError(ListenRules.darija(SpeechRecognizer.ERROR_NO_MATCH)) else callbacks.onFinal(text)
            }

            override fun onError(error: Int) {
                DebugLog.add("listen error $error ($language)")
                if (ListenRules.shouldRetryWithFallback(error, language)) {
                    language = ListenRules.FALLBACK_LANGUAGE
                    begin()
                    return
                }
                val heard = lastPartial?.trim().orEmpty()
                finish()
                // Silence after some words: use what was heard instead of an error.
                if (heard.isNotEmpty() && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) {
                    callbacks.onFinal(heard)
                } else {
                    callbacks.onError(ListenRules.darija(error))
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        isListening = true
        r.startListening(intent)
    }

    private fun finish() {
        isListening = false
        recognizer?.destroy()
        recognizer = null
    }
}

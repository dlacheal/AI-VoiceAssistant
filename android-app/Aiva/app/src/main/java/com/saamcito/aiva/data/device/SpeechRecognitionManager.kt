package com.saamcito.aiva.data.device

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class SpeechRecognitionManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    private val _recognitionResults = MutableSharedFlow<RecognitionResult>(extraBufferCapacity = 1)
    val recognitionResults: SharedFlow<RecognitionResult> = _recognitionResults

    fun init() {
        if (speechRecognizer != null) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val textos = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val texto = textos?.firstOrNull() ?: ""
                _recognitionResults.tryEmit(RecognitionResult.Success(texto))
            }

            override fun onError(error: Int) {
                _recognitionResults.tryEmit(RecognitionResult.Error(error))
            }

            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-PE")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    sealed class RecognitionResult {
        data class Success(val text: String) : RecognitionResult()
        data class Error(val errorCode: Int) : RecognitionResult()
    }
}

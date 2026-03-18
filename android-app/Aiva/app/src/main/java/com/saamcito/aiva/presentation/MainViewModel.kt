package com.saamcito.aiva.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.saamcito.aiva.data.device.SpeechRecognitionManager
import com.saamcito.aiva.data.device.TtsManager
import com.saamcito.aiva.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MainUiState(
    val estadoTexto: String = "✅ Aiva lista",
    val inputLabel: String = "Presiona 🎙️ para hablar...",
    val isBotonHablarHabilitado: Boolean = true,
    val mensajes: List<UiChatMessage> = emptyList(),
    val scrollToBottom: Boolean = false
)

class MainViewModel(
    private val repository: ChatRepository,
    private val ttsManager: TtsManager,
    private val speechRecognitionManager: SpeechRecognitionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        speechRecognitionManager.init()
        observeSpeechRecognition()
    }

    private fun now() = timeFormat.format(Date())

    private fun observeSpeechRecognition() {
        viewModelScope.launch {
            speechRecognitionManager.recognitionResults.collect { result ->
                when (result) {
                    is SpeechRecognitionManager.RecognitionResult.Success -> {
                        if (result.text.isNotEmpty()) {
                            // Agregar mensaje del usuario al historial
                            val userMsg = UiChatMessage(UiChatMessage.Sender.USER, result.text, now())
                            _uiState.update { it.copy(
                                estadoTexto = "⏳ Enviando...",
                                inputLabel = "\"${result.text}\"",
                                isBotonHablarHabilitado = false,
                                mensajes = it.mensajes + userMsg,
                                scrollToBottom = true
                            ) }
                            enviarMensaje(result.text)
                        } else {
                            _uiState.update { it.copy(
                                estadoTexto = "✅ Aiva lista",
                                isBotonHablarHabilitado = true
                            ) }
                        }
                    }
                    is SpeechRecognitionManager.RecognitionResult.Error -> {
                        _uiState.update { it.copy(
                            estadoTexto = "❌ Error de voz (${result.errorCode})",
                            inputLabel = "Presiona 🎙️ para hablar...",
                            isBotonHablarHabilitado = true
                        ) }
                    }
                }
            }
        }
    }

    fun onBotonHablarClic() {
        _uiState.update { it.copy(
            estadoTexto = "🎙️ Escuchando...",
            inputLabel = "Escuchando...",
            isBotonHablarHabilitado = false
        ) }
        speechRecognitionManager.startListening()
    }

    private fun enviarMensaje(texto: String) {
        viewModelScope.launch {
            val respuesta = repository.sendMessage(texto)
            when {
                respuesta == null -> {
                    _uiState.update { it.copy(estadoTexto = "❌ Sin respuesta", isBotonHablarHabilitado = true, inputLabel = "Presiona 🎙️ para hablar...") }
                }
                respuesta.startsWith("ERROR_HTTP|") || respuesta.startsWith("ERROR_CONN|") -> {
                    val aiMsg = UiChatMessage(UiChatMessage.Sender.AI, "⚠️ $respuesta", now())
                    _uiState.update { it.copy(
                        estadoTexto = "❌ Error",
                        inputLabel = "Presiona 🎙️ para hablar...",
                        isBotonHablarHabilitado = true,
                        mensajes = it.mensajes + aiMsg,
                        scrollToBottom = true
                    ) }
                }
                else -> {
                    val aiMsg = UiChatMessage(UiChatMessage.Sender.AI, respuesta, now())
                    _uiState.update { it.copy(
                        estadoTexto = "✅ Aiva lista",
                        inputLabel = "Presiona 🎙️ para hablar...",
                        isBotonHablarHabilitado = true,
                        mensajes = it.mensajes + aiMsg,
                        scrollToBottom = true
                    ) }
                    ttsManager.speak(respuesta)
                }
            }
        }
    }

    fun onScrollDone() {
        _uiState.update { it.copy(scrollToBottom = false) }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
        speechRecognitionManager.destroy()
    }
}

class MainViewModelFactory(
    private val repository: ChatRepository,
    private val ttsManager: TtsManager,
    private val speechRecognitionManager: SpeechRecognitionManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, ttsManager, speechRecognitionManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

package com.saamcito.aiva.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.saamcito.aiva.R
import com.saamcito.aiva.data.device.SpeechRecognitionManager
import com.saamcito.aiva.data.device.TtsManager
import com.saamcito.aiva.data.network.AivaHttpClient
import com.saamcito.aiva.data.repository.ChatRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var rvMessages: RecyclerView
    private lateinit var tvEstado: TextView
    private lateinit var tvInput: TextView
    private lateinit var btnHablar: FloatingActionButton
    private lateinit var chatAdapter: ChatAdapter

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            ChatRepository(AivaHttpClient()),
            TtsManager(applicationContext),
            SpeechRecognitionManager(applicationContext)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvEstado  = findViewById(R.id.tvEstado)
        tvInput   = findViewById(R.id.tvRespuesta)
        btnHablar = findViewById(R.id.btnHablar)
        rvMessages = findViewById(R.id.rvMessages)

        // Configurar RecyclerView
        chatAdapter = ChatAdapter()
        rvMessages.adapter = chatAdapter
        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true   // El scroll empieza desde abajo
        }

        pedirPermisos()

        btnHablar.setOnClickListener {
            viewModel.onBotonHablarClic()
        }

        observeUiState()
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    tvEstado.text  = state.estadoTexto
                    tvInput.text   = state.inputLabel
                    btnHablar.isEnabled = state.isBotonHablarHabilitado

                    // Renderizar nuevos mensajes del historial
                    if (state.mensajes.size > chatAdapter.itemCount) {
                        val nuevos = state.mensajes.drop(chatAdapter.itemCount)
                        nuevos.forEach { chatAdapter.addMessage(it) }
                    }

                    // Auto-scroll al último mensaje
                    if (state.scrollToBottom && chatAdapter.itemCount > 0) {
                        rvMessages.smoothScrollToPosition(chatAdapter.itemCount - 1)
                        viewModel.onScrollDone()
                    }
                }
            }
        }
    }

    private fun pedirPermisos() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 1
            )
        }
    }
}

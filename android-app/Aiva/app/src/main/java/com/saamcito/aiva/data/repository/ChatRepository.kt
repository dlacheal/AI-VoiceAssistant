package com.saamcito.aiva.data.repository

import com.saamcito.aiva.data.network.AivaHttpClient

class ChatRepository(
    private val httpClient: AivaHttpClient
) {
    suspend fun sendMessage(text: String): String? {
        return httpClient.sendMessage(text)
    }
}

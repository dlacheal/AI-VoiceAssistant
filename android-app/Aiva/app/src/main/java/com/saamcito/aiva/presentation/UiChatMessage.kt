package com.saamcito.aiva.presentation

data class UiChatMessage(
    val sender: Sender,
    val text: String,
    val timestamp: String
) {
    enum class Sender { USER, AI }
}

package com.saamcito.agente.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configura el ChatClient de Spring AI pre-cargado con el system prompt
 * para el asistente de voz AIVA.
 */
@Configuration
public class AiConfig {

    private static final String SYSTEM_PROMPT = """
            Eres AIVA, un asistente de voz inteligente creado por Saamcito.
            Responde siempre en español de forma clara, concisa y natural, 
            como si estuvieras hablando en voz alta.
            Si tienes contexto adicional relevante disponible, úsalo para enriquecer tu respuesta.
            Evita usar markdown, listas ni formatos especiales en tus respuestas; 
            habla en prose natural que suene bien al ser convertido a audio.
            """;

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

}

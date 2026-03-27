package com.saamcito.agente.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de IA que mantiene el historial de conversación por sesión
 * y delega las llamadas a DeepSeek mediante Spring AI ChatClient.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final ChatClient chatClient;

    /**
     * Mapa de conversaciones activas: conversationId → lista de mensajes.
     * ConcurrentHashMap para soporte multi-hilo básico.
     * En producción considerar Redis o una base de datos.
     */
    private final Map<String, List<Message>> conversations = new ConcurrentHashMap<>();

    /**
     * Procesa un mensaje del usuario dentro de una conversación,
     * manteniendo el historial completo para dar contexto a DeepSeek.
     *
     * @param userMessage    Texto enviado por AIVA
     * @param conversationId ID único de la sesión de AIVA
     * @return Respuesta generada por DeepSeek
     */
    public String chat(String userMessage, String conversationId) {
        log.debug("Chat request - conversationId: {}, message: {}", conversationId, userMessage);

        List<Message> history = conversations.computeIfAbsent(conversationId, id -> new ArrayList<>());

        // Agregar el mensaje del usuario al historial
        history.add(new UserMessage(userMessage));

        // Llamar a DeepSeek con todo el historial
        String reply = chatClient.prompt()
                .messages(history)
                .call()
                .content();

        // Guardar la respuesta en el historial
        history.add(new AssistantMessage(reply));

        log.debug("Chat response - conversationId: {}, reply: {}", conversationId, reply);
        return reply;
    }

    /**
     * Elimina el historial de una conversación específica.
     * Útil cuando AIVA inicia una nueva sesión.
     */
    public void clearConversation(String conversationId) {
        conversations.remove(conversationId);
        log.info("Conversation cleared: {}", conversationId);
    }

    /**
     * Limpia todas las conversaciones activas.
     */
    public void clearAllConversations() {
        conversations.clear();
        log.info("All conversations cleared");
    }

    /**
     * Retorna el número de turnos en una conversación.
     */
    public int getConversationLength(String conversationId) {
        return conversations.getOrDefault(conversationId, List.of()).size();
    }
}

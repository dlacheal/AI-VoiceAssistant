package com.saamcito.agente.rag.controller;

import com.saamcito.agente.rag.dto.ChatRequest;
import com.saamcito.agente.rag.dto.ChatResponse;
import com.saamcito.agente.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint principal de chat para la app Android AIVA.
 *
 * POST /api/chat            → Envía un mensaje, recibe la respuesta de DeepSeek (con RAG)
 * DELETE /api/chat/{id}     → Limpia el historial de una conversación
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final RagService ragService;

    /**
     * Endpoint principal: AIVA envía el texto (transcrito de voz) y
     * este backend lo enriquece con contexto RAG y responde con DeepSeek.
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("Received chat request from conversationId: {}", request.getConversationId());

        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ChatResponse("El mensaje no puede estar vacío.", request.getConversationId()));
        }

        String conversationId = request.getConversationId() != null
                ? request.getConversationId()
                : "default";

        String reply = ragService.chatWithContext(request.getMessage(), conversationId);

        return ResponseEntity.ok(new ChatResponse(reply, conversationId));
    }

    /**
     * Limpia el historial de conversación (útil cuando el usuario inicia una nueva sesión en AIVA).
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> clearConversation(@PathVariable String conversationId) {
        ragService.clearConversation(conversationId);
        log.info("Conversation cleared: {}", conversationId);
        return ResponseEntity.noContent().build();
    }
}

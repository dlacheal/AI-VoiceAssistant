package com.saamcito.agente.rag.dto;

import lombok.Data;

@Data
public class ChatRequest {

    /** Texto del mensaje del usuario (puede venir transcrito de voz). */
    private String message;

    /**
     * ID de sesión de la conversación.
     * AIVA debe generar uno (ej. UUID) y reutilizarlo para mantener contexto.
     */
    private String conversationId;
}

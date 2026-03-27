package com.saamcito.agente.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ChatResponse {

    /** Respuesta generada por DeepSeek. */
    private String reply;

    /** El mismo conversationId que llegó en el request (para que AIVA lo vincule). */
    private String conversationId;
}

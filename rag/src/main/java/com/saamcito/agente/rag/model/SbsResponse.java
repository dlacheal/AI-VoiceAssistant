package com.saamcito.agente.rag.model;

/**
 * Modelo de salida estructurada (Output Guardrail) para el bot de normativas SBS.
 * Garantiza que la respuesta del LLM siempre contenga la respuesta y sus referencias.
 */
public record SbsResponse(
    String respuesta,
    String referencia_legal
) {}

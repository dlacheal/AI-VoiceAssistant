package com.saamcito.agente.rag.dto;

public record EvaluationResponse(
    double score,
    String methodUsed,
    String reasoning
) {}

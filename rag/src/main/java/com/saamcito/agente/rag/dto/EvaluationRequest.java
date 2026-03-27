package com.saamcito.agente.rag.dto;

public record EvaluationRequest(
    String generatedAnswer,
    String expectedAnswer
) {}

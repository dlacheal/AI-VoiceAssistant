package com.saamcito.agente.rag.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saamcito.agente.rag.dto.EvaluationResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class HybridScoringService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // The threshold to fallback to LLM
    private static final double LLM_FALLBACK_THRESHOLD = 40.0;

    public HybridScoringService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Executes the Hybrid Scoring evaluation.
     * Starts with a fast, zero-cost Jaccard index calculation.
     * Falls back to LLM-as-a-judge if the initial score is less than the threshold.
     */
    public EvaluationResponse evaluateAnswer(String generatedAnswer, String expectedAnswer) {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startTime = System.currentTimeMillis();

        if (generatedAnswer == null || expectedAnswer == null) {
            return new EvaluationResponse(0.0, "ERROR", "Answers cannot be null");
        }

        // 1. Fast Path: Token-Based Simple Evaluation (Jaccard Index)
        double simpleScore = calculateJaccardIndex(generatedAnswer, expectedAnswer);
        log.info("Similitud Jaccard Calculada: {} (Tiempo: {}ms)", simpleScore, (System.currentTimeMillis() - startTime));

        // Return immediately if the lexical similarity is high enough
        if (simpleScore >= LLM_FALLBACK_THRESHOLD) {
            meterRegistry.counter("rag.scoring.validations", "method", "SIMPLE").increment();
            sample.stop(meterRegistry.timer("rag.scoring.latency", "method", "SIMPLE"));
            return new EvaluationResponse(
                    simpleScore, 
                    "SIMPLE", 
                    "Fast lexical similarity matched with high confidence."
            );
        }

        // 2. Slow Path: LLM-as-a-Judge for semantic similarity
        log.info("Score muy bajo ({} < {}). Ejecutando LLM Fallback...", simpleScore, LLM_FALLBACK_THRESHOLD);
        meterRegistry.counter("rag.scoring.validations", "method", "LLM").increment();
        EvaluationResponse llmResponse = performLlmSemanticEvaluation(generatedAnswer, expectedAnswer);
        sample.stop(meterRegistry.timer("rag.scoring.latency", "method", "LLM"));
        return llmResponse;
    }

    /**
     * Calculates Jaccard Index (Intersection over Union of words).
     * @return score from 0.0 to 100.0
     */
    private double calculateJaccardIndex(String s1, String s2) {
        Set<String> words1 = new HashSet<>(Arrays.asList(s1.toLowerCase().replaceAll("[^a-záéíóúñ0-9\\s]", "").split("\\s+")));
        Set<String> words2 = new HashSet<>(Arrays.asList(s2.toLowerCase().replaceAll("[^a-záéíóúñ0-9\\s]", "").split("\\s+")));

        if (words1.isEmpty() && words2.isEmpty()) return 100.0;
        if (words1.isEmpty() || words2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(words1);
        intersection.retainAll(words2);

        Set<String> union = new HashSet<>(words1);
        union.addAll(words2);

        return ((double) intersection.size() / union.size()) * 100.0;
    }

    /**
     * Internal structure to parse LLM Response JSON safely
     */
    private record LlmEvaluationResult(
            @JsonProperty("score") double score,
            @JsonProperty("reasoning") String reasoning
    ) {}

    /**
     * Calls the Large Language Model to evaluate semantic correctness.
     */
    private EvaluationResponse performLlmSemanticEvaluation(String generatedAnswer, String expectedAnswer) {
        long startTime = System.currentTimeMillis();
        
        String systemPrompt = """
                [ROLE]: Evaluador estricto.
                [TASK]: Compara si 'Generated Answer' es semánticamente idéntica a 'Expected Answer'.
                [OUTPUT FORMAT]: SOLO JSON estricto.
                {"score": 0.0, "reasoning": "breve explicacion"}
                """;
                
        String userPrompt = String.format(
                "Expected Answer: %s\nGenerated Answer: %s", 
                expectedAnswer, 
                generatedAnswer
        );

        try {
            String jsonResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
                    
            LlmEvaluationResult result = parseLlmResponse(jsonResponse);
            
            log.info("LLM Evaluation Completada en {}ms, Score: {}", (System.currentTimeMillis() - startTime), result.score());
            return new EvaluationResponse(result.score(), "LLM", result.reasoning());
            
        } catch (Exception e) {
            log.error("Fallo al ejecutar evaluación LLM, usando score por defecto (0.0): {}", e.getMessage(), e);
            return new EvaluationResponse(0.0, "LLM_ERROR", "Evaluation failed: " + e.getMessage());
        }
    }

    private LlmEvaluationResult parseLlmResponse(String text) {
        try {
            String cleaned = text.trim();
            int firstBrace = cleaned.indexOf("{");
            int lastBrace = cleaned.lastIndexOf("}");
            
            if (firstBrace == -1 || lastBrace == -1) {
                return new LlmEvaluationResult(0.0, "El LLM no generó un JSON válido");
            }
            
            cleaned = cleaned.substring(firstBrace, lastBrace + 1);
            return objectMapper.readValue(cleaned, LlmEvaluationResult.class);
        } catch (Exception e) {
            log.error("Error parseando respuesta JSON del LLM de Evaluación: {}", text, e);
            throw new RuntimeException("Error parseando respuesta LLM", e);
        }
    }
}

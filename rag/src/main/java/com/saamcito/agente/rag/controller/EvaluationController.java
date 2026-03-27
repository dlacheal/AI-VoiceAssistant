package com.saamcito.agente.rag.controller;

import com.saamcito.agente.rag.dto.EvaluationRequest;
import com.saamcito.agente.rag.dto.EvaluationResponse;
import com.saamcito.agente.rag.service.HybridScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluate")
@RequiredArgsConstructor
public class EvaluationController {

    private final HybridScoringService hybridScoringService;

    @PostMapping("/scoring")
    public ResponseEntity<EvaluationResponse> evaluate(@RequestBody EvaluationRequest request) {
        EvaluationResponse response = hybridScoringService.evaluateAnswer(
                request.generatedAnswer(),
                request.expectedAnswer()
        );
        return ResponseEntity.ok(response);
    }
}

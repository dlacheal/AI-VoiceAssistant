package com.saamcito.agente.rag.controller;

import com.saamcito.agente.rag.model.SbsResponse;import com.saamcito.agente.rag.service.SbsRagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sbs")
public class SbsRagController {

    private final SbsRagService sbsRagService;

    public SbsRagController(SbsRagService sbsRagService) {
        this.sbsRagService = sbsRagService;
    }

    @PostMapping("/index")
    public ResponseEntity<Map<String, String>> indexDocuments() {
        String result = sbsRagService.indexDocuments();
        return ResponseEntity.ok(Map.of("message", result));
    }

    @GetMapping("/ask")
    public ResponseEntity<SbsResponse> askQuestion(@RequestParam String question) {
        SbsResponse answer = sbsRagService.askQuestion(question);
        return ResponseEntity.ok(answer);
    }
}

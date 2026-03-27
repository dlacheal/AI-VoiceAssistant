package com.saamcito.agente.rag.controller;

import com.saamcito.agente.rag.dto.IngestRequest;
import com.saamcito.agente.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints para gestionar los documentos del vector store (base de conocimiento de AIVA).
 *
 * POST /api/documents          → Ingesta un nuevo documento en el vector store
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final RagService ragService;

    /**
     * Ingesta un documento de texto en el vector store.
     * Úsalo para cargar FAQs, manuales, o cualquier conocimiento que AIVA deba recordar.
     *
     * Ejemplo de body:
     * {
     *   "content": "El asistente AIVA fue creado por Saamcito en 2025.",
     *   "source": "about-aiva"
     * }
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> ingest(@RequestBody IngestRequest request) {
        if (request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "El contenido no puede estar vacío."));
        }

        ragService.ingestText(request.getContent(), request.getSource());
        log.info("Document ingested from source: {}", request.getSource());

        return ResponseEntity.ok(Map.of(
                "status", "ingested",
                "source", request.getSource() != null ? request.getSource() : "manual",
                "chars", String.valueOf(request.getContent().length())
        ));
    }
}

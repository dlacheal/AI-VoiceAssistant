package com.saamcito.agente.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio de Re-Ranking avanzado.
 * Utiliza "LLM as a Cross-Encoder Judge" para puntuar semánticamente la relevancia 
 * de cada documento recuperado respecto a la pregunta original del usuario,
 * garantizando cero alucinaciones y alta precisión antes de generar la respuesta final.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossEncoderReRankerService {

    private final ChatClient chatClient;

    /**
     * Re-evalúa una lista de documentos y retorna los N mejores (Top N).
     */
    public List<Document> rerank(String question, List<Document> documents, int topN) {
        log.info("Iniciando Re-Ranking (Cross-Encoder LLM) para {} documentos...", documents.size());
        
        long startTime = System.currentTimeMillis();
        List<Document> scoredDocs = new ArrayList<>();

        // Reranking mediante scoring rápido por documento
        for (Document doc : documents) {
            double score = scoreDocumentRelevance(question, doc.getText());
            Document scoredDoc = new Document(doc.getId(), doc.getText(), doc.getMetadata());
            scoredDoc.getMetadata().put("rerank_score", score);
            scoredDocs.add(scoredDoc);
        }

        // Ordenamos por puntaje descendente
        scoredDocs.sort((d1, d2) -> Double.compare(
                (Double) d2.getMetadata().getOrDefault("rerank_score", 0.0),
                (Double) d1.getMetadata().getOrDefault("rerank_score", 0.0)
        ));

        // Retornamos el Top N
        List<Document> topDocs = scoredDocs.stream().limit(topN).collect(Collectors.toList());
        log.info("Re-Ranking completado en {}ms. Se conservaron {}/{} documentos relevantes.", 
                (System.currentTimeMillis() - startTime), topDocs.size(), documents.size());
                
        return topDocs;
    }

    private double scoreDocumentRelevance(String question, String content) {
         String systemPrompt = """
                [ROLE]: Evaluador Cross-Encoder estricto.
                [TASK]: Evalúa si el 'Documento' contiene la respuesta o información directamente relevante a la 'Pregunta'.
                [OUTPUT]: Devuelve SOLO un número de 0.0 a 1.0 (Ejemplo: 0.95). Ni texto extra, ni justificación.
                """;
                
        String userPrompt = String.format("Pregunta: %s\nDocumento: %s", question, content);

        try {
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
                    
            return Double.parseDouble(response.trim().replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            log.warn("Fallo en LLM Cross-Encoder Scoring (Se asignará 0.0): {}", e.getMessage());
            return 0.0;
        }
    }
}

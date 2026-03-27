package com.saamcito.agente.rag.dto;

import lombok.Data;

@Data
public class IngestRequest {

    /** Texto que se almacenará en el vector store para enriquecer respuestas futuras. */
    private String content;

    /** Metadato opcional: fuente u origen del documento. */
    private String source;
}

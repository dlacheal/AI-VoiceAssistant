package com.saamcito.agente.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Executors;

@Service
public class PdfFileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(PdfFileWatcherService.class);

    private final SbsRagService sbsRagService;
    private final String watchPath;

    public PdfFileWatcherService(SbsRagService sbsRagService, 
                                 @Value("${rag.document-watcher.path:./fedora-docs}") String watchPath) {
        this.sbsRagService = sbsRagService;
        this.watchPath = watchPath;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWatcher() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Path path = Paths.get(watchPath);
                if (!Files.exists(path)) {
                    Files.createDirectories(path);
                    log.info("Carpeta creada para observar documentos: {}", path.toAbsolutePath());
                }

                WatchService watchService = FileSystems.getDefault().newWatchService();
                path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                
                log.info("File Watcher iniciado en el directorio: {}", path.toAbsolutePath());

                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watchService.take();
                    
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }

                        Path filename = (Path) event.context();
                        String fileStr = filename.toString();
                        
                        if (fileStr.toLowerCase().endsWith(".pdf")) {
                            log.info("Detectado cambio en archivo PDF: {}. Procesando ingesta...", fileStr);
                            
                            // Esperar un poco para asegurar que el archivo haya terminado de copiarse
                            Thread.sleep(1500); 

                            File pdfFile = path.resolve(filename).toFile();
                            if (pdfFile.exists() && pdfFile.canRead()) {
                                sbsRagService.indexSingleDocument(new FileSystemResource(pdfFile));
                            }
                        }
                    }
                    
                    if (!key.reset()) {
                        log.warn("El watch key ya no es válido, se detiene el watcher.");
                        break;
                    }
                }
            } catch (IOException e) {
                log.error("Error al configurar el File Watcher", e);
            } catch (InterruptedException e) {
                log.error("El File Watcher fue interrumpido", e);
                Thread.currentThread().interrupt();
            }
        });
    }
}

package co.edu.retencion.imports.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.retencion.imports.infrastructure.ImportJdbc;

/**
 * Procesa en segundo plano los archivos grandes. Cualquier instancia puede tomar un trabajo; el
 * bloqueo {@code SKIP LOCKED} garantiza que no se procese dos veces. Los trabajos huérfanos (de
 * una instancia caída) se reencolan.
 */
@Component
class ImportWorker {

    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);

    private final ImportJdbc jdbc;
    private final ImportProcessor processor;
    private final ImportProperties properties;
    private final Clock clock;

    ImportWorker(ImportJdbc jdbc, ImportProcessor processor, ImportProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.imports.poll-interval-ms:2000}", initialDelayString = "${app.imports.poll-interval-ms:2000}")
    void poll() {
        int requeued = jdbc.requeueStale(clock.instant(), properties.staleAfter());
        if (requeued > 0) {
            log.warn("Se reencolaron {} importaciones huérfanas", requeued);
        }
        Optional<UUID> next;
        while ((next = jdbc.claimNext(clock.instant())).isPresent()) {
            processor.process(next.get());
        }
    }
}

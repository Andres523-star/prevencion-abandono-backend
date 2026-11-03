package co.edu.retencion.audit.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.audit.domain.AuditEntry;
import co.edu.retencion.audit.domain.ChainVerification;
import co.edu.retencion.audit.domain.HashChain;
import co.edu.retencion.audit.infrastructure.AuditLogReader;

/**
 * Recorre la bitácora completa y recalcula la cadena de hashes. Detecta filas alteradas
 * (hash distinto), borradas (salto en la secuencia) o reordenadas (prev_hash inconsistente).
 */
@Service
public class AuditChainVerifier {

    private static final int BATCH = 1_000;

    private final AuditLogReader reader;

    public AuditChainVerifier(AuditLogReader reader) {
        this.reader = reader;
    }

    @Transactional(readOnly = true)
    public ChainVerification verify() {
        long expectedSeq = 1;
        String previousHash = HashChain.GENESIS;
        long checked = 0;
        List<AuditEntry> batch;
        do {
            batch = reader.readAfter(expectedSeq - 1, BATCH);
            for (AuditEntry entry : batch) {
                if (entry.seq() != expectedSeq) {
                    return ChainVerification.broken(checked, expectedSeq, "Falta la entrada esperada (posible borrado)");
                }
                if (!entry.prevHash().equals(previousHash)) {
                    return ChainVerification.broken(checked, entry.seq(), "prev_hash no coincide con la entrada anterior");
                }
                String recomputed = HashChain.compute(previousHash, entry.seq(), entry);
                if (!recomputed.equals(entry.hash())) {
                    return ChainVerification.broken(checked, entry.seq(), "El contenido de la entrada fue alterado");
                }
                previousHash = entry.hash();
                expectedSeq++;
                checked++;
            }
        } while (batch.size() == BATCH);
        return ChainVerification.ok(checked);
    }
}

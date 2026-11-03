package co.edu.retencion.imports.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import co.edu.retencion.imports.domain.RowOutcome;

/**
 * Acceso JDBC para lo que no conviene pasar por JPA: el contenido binario del archivo, los
 * resultados por fila (miles por trabajo, insertados por lotes) y la toma de trabajos pendientes
 * con {@code FOR UPDATE SKIP LOCKED} (varias instancias no procesan el mismo trabajo).
 */
@Component
public class ImportJdbc {

    private static final int BATCH = 1_000;

    private final JdbcTemplate jdbc;

    public ImportJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void saveFile(UUID jobId, byte[] content) {
        jdbc.update("INSERT INTO import_files (job_id, content) VALUES (?, ?)", jobId, content);
    }

    public Optional<byte[]> loadFile(UUID jobId) {
        List<byte[]> rows = jdbc.query("SELECT content FROM import_files WHERE job_id = ?",
                (rs, i) -> rs.getBytes(1), jobId);
        return rows.stream().findFirst();
    }

    /** Borra el archivo (contiene datos personales) apenas se termina de procesar. */
    public void deleteFile(UUID jobId) {
        jdbc.update("DELETE FROM import_files WHERE job_id = ?", jobId);
    }

    /**
     * Resultado de una fila para persistir.
     *
     * @param rowNumber número de fila en el archivo
     * @param outcome   resultado
     */
    public record RowResult(int rowNumber, RowOutcome outcome) {
    }

    public void writeResults(UUID jobId, List<RowResult> results) {
        for (int from = 0; from < results.size(); from += BATCH) {
            List<RowResult> chunk = results.subList(from, Math.min(from + BATCH, results.size()));
            jdbc.batchUpdate("""
                    INSERT INTO import_row_results (job_id, row_number, outcome, message) VALUES (?, ?, ?, ?)
                    ON CONFLICT (job_id, row_number) DO UPDATE SET outcome = EXCLUDED.outcome, message = EXCLUDED.message
                    """, chunk, chunk.size(), (ps, r) -> {
                ps.setObject(1, jobId);
                ps.setInt(2, r.rowNumber());
                ps.setString(3, r.outcome().result().name());
                String message = r.outcome().message();
                ps.setString(4, message == null ? null : message.substring(0, Math.min(message.length(), 1000)));
            });
        }
    }

    public List<RowResult> results(UUID jobId) {
        return jdbc.query("SELECT row_number, outcome, message FROM import_row_results WHERE job_id = ? ORDER BY row_number",
                (rs, i) -> new RowResult(rs.getInt(1),
                        new RowOutcome(RowOutcome.Result.valueOf(rs.getString(2)), rs.getString(3))), jobId);
    }

    /** Toma el trabajo pendiente más antiguo y lo marca en proceso, de forma atómica. */
    public Optional<UUID> claimNext(Instant now) {
        List<UUID> ids = jdbc.query("""
                UPDATE import_jobs SET status = 'PROCESSING', started_at = ?, version = version + 1
                WHERE id = (SELECT id FROM import_jobs WHERE status = 'PENDING' ORDER BY created_at
                            LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING id
                """, (rs, i) -> rs.getObject(1, UUID.class), Timestamp.from(now));
        return ids.stream().findFirst();
    }

    /** Devuelve a la cola los trabajos que quedaron en proceso por una instancia caída. */
    public int requeueStale(Instant now, Duration maxProcessing) {
        return jdbc.update("""
                UPDATE import_jobs SET status = 'PENDING', version = version + 1
                WHERE status = 'PROCESSING' AND started_at < ?
                """, Timestamp.from(now.minus(maxProcessing)));
    }
}

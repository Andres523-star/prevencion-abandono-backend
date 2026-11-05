package co.edu.retencion.reports.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Cola y consulta de reportes. */
public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {

    /**
     * Toma el siguiente trabajo (pendiente o huérfano de una instancia caída). {@code SKIP LOCKED}: dos
     * instancias nunca toman el mismo.
     */
    @Query(value = """
            SELECT id FROM report_jobs
            WHERE status = 'PENDIENTE' OR (status = 'PROCESANDO' AND lease_until < :now)
            ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> nextId(Instant now);

    @Modifying
    @Query(value = """
            UPDATE report_jobs SET status = 'PROCESANDO', lease_until = :lease, attempts = attempts + 1, version = version + 1
            WHERE id = :id
            """, nativeQuery = true)
    int lease(UUID id, Instant lease);

    @Query("""
            select j from ReportJob j where j.requestedBy = :user and (:fromStart = true or j.id < :before)
            order by j.id desc
            """)
    List<ReportJob> mine(UUID user, boolean fromStart, UUID before, Limit limit);

    @Query("select j from ReportJob j where j.status = 'LISTO' and j.expiresAt <= :now")
    List<ReportJob> expired(Instant now);
}

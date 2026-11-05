package co.edu.retencion.alerts.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Repositorio de alertas. */
public interface AlertRepository extends JpaRepository<Alert, UUID> {

    @Query("select a from Alert a where a.dedupeKey = :key and a.status in ('NUEVA', 'VISTA')")
    Optional<Alert> findOpenByKey(String key);

    long countByAssignedToAndStatusIn(UUID assignedTo, Collection<Alert.Status> statuses);

    @Query("select a from Alert a where a.status in ('NUEVA', 'VISTA') order by a.createdAt")
    List<Alert> findOpen();

    List<Alert> findByStudentIdOrderByIdDesc(UUID studentId);

    @Query("""
            select count(a) from Alert a where a.status in ('NUEVA', 'VISTA')
              and (:anyProgram = true or a.programId in :programs)
            """)
    long countOpen(boolean anyProgram, Collection<UUID> programs);

    @Query("""
            select a from Alert a
            where (:mine = false or a.assignedTo = :user)
              and (:anyProgram = true or a.programId in :programs)
              and (:anyStatus = true or a.status = :status)
              and (:fromStart = true or a.id < :before)
            order by a.id desc
            """)
    List<Alert> search(boolean mine, UUID user, boolean anyProgram, Collection<UUID> programs, boolean anyStatus,
                       Alert.Status status, boolean fromStart, UUID before, Limit limit);
}

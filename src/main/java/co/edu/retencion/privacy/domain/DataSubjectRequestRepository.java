package co.edu.retencion.privacy.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Solicitudes del titular. */
public interface DataSubjectRequestRepository extends JpaRepository<DataSubjectRequest, UUID> {

    List<DataSubjectRequest> findByStudentIdOrderByCreatedAtDesc(UUID studentId);

    @Query("""
            select r from DataSubjectRequest r
            where (:anyStatus = true or r.status = :status)
            order by r.dueOn, r.createdAt
            """)
    List<DataSubjectRequest> queue(boolean anyStatus, DataSubjectRequest.Status status);

    @Query("select r from DataSubjectRequest r where r.exportObjectKey is not null and r.exportExpiresAt <= :now")
    List<DataSubjectRequest> expiredExports(Instant now);
}

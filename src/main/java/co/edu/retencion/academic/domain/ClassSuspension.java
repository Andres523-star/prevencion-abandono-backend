package co.edu.retencion.academic.domain;

import co.edu.retencion.common.error.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/** Suspensión de clases en un rango de fechas, global o de un programa. */
@Entity
@Table(name = "class_suspensions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassSuspension {

    @Id
    private UUID id;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** {@code null} = aplica a todos los programas. */
    @Column(name = "program_id")
    private UUID programId;

    @Column(nullable = false)
    private String reason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ClassSuspension create(LocalDate start, LocalDate end, UUID programId, String reason,
                                         UUID createdBy, Instant now) {
        if (end.isBefore(start)) {
            throw DomainException.badRequest("La fecha de fin no puede ser anterior a la de inicio");
        }
        ClassSuspension s = new ClassSuspension();
        s.id = UuidV7.newId();
        s.startDate = start;
        s.endDate = end;
        s.programId = programId;
        s.reason = reason;
        s.createdBy = createdBy;
        s.createdAt = now;
        return s;
    }

    /** Si la suspensión cubre la fecha para el programa dado. */
    public boolean covers(LocalDate date, UUID program) {
        boolean inRange = !date.isBefore(startDate) && !date.isAfter(endDate);
        return inRange && (programId == null || programId.equals(program));
    }
}

package co.edu.retencion.academic.domain;

import co.edu.retencion.common.error.DomainException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/** Periodo académico (p. ej. 2026-2). */
@Entity
@Table(name = "terms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Term {

    /** Estado del periodo. */
    public enum Status {
        PLANNED,
        ACTIVE,
        CLOSED
    }

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static Term create(String code, String name, LocalDate start, LocalDate end, Status status, Instant now) {
        validateDates(start, end);
        Term term = new Term();
        term.id = UuidV7.newId();
        term.code = code;
        term.name = name;
        term.startDate = start;
        term.endDate = end;
        term.status = status;
        term.createdAt = now;
        term.updatedAt = now;
        return term;
    }

    public void update(String name, LocalDate start, LocalDate end, Status status, Instant now) {
        validateDates(start, end);
        if (this.status == Status.CLOSED && status != Status.CLOSED) {
            throw DomainException.badRequest("Un periodo cerrado no se puede reabrir");
        }
        this.name = name;
        this.startDate = start;
        this.endDate = end;
        this.status = status;
        this.updatedAt = now;
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (!end.isAfter(start)) {
            throw DomainException.badRequest("La fecha de fin debe ser posterior a la de inicio");
        }
        if (start.plusYears(1).isBefore(end)) {
            throw DomainException.badRequest("Un periodo no puede durar más de un año");
        }
    }
}

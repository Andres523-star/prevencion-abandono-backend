package co.edu.retencion.analytics.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/**
 * Dato histórico de deserción: matriculados y desertores de un programa en un mes. Viene de periodos
 * anteriores a la plataforma, ya agregado: no hay estudiantes detrás.
 */
@Entity
@Table(name = "dropout_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropoutHistory {

    @Id
    private UUID id;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    private Integer semester;

    @Column(nullable = false)
    private int enrolled;

    @Column(nullable = false)
    private int dropouts;

    private String label;

    @Column(name = "imported_by")
    private UUID importedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static DropoutHistory of(UUID programId, YearMonth month, Integer semester, int enrolled, int dropouts,
                                    String label, UUID actor, Instant now) {
        requireValid(enrolled, dropouts);
        DropoutHistory h = new DropoutHistory();
        h.id = UuidV7.newId();
        h.programId = programId;
        h.periodMonth = month.atDay(1);
        h.semester = semester;
        h.enrolled = enrolled;
        h.dropouts = dropouts;
        h.label = label;
        h.importedBy = actor;
        h.createdAt = now;
        h.updatedAt = now;
        return h;
    }

    /** @return {@code true} si algo cambió */
    public boolean update(int enrolled, int dropouts, String label, UUID actor, Instant now) {
        requireValid(enrolled, dropouts);
        boolean same = this.enrolled == enrolled && this.dropouts == dropouts && java.util.Objects.equals(this.label, label);
        if (same) {
            return false;
        }
        this.enrolled = enrolled;
        this.dropouts = dropouts;
        this.label = label;
        this.importedBy = actor;
        this.updatedAt = now;
        return true;
    }

    private static void requireValid(int enrolled, int dropouts) {
        if (enrolled < 0 || dropouts < 0 || dropouts > enrolled) {
            throw DomainException.badRequest("Los desertores deben estar entre 0 y los matriculados");
        }
    }
}

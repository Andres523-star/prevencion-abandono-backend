package co.edu.retencion.academic.domain;

import co.edu.retencion.common.error.DomainException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/** Materia de un programa, con créditos, semestre sugerido y prerrequisitos. */
@Entity
@Table(name = "subjects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subject {

    @Id
    private UUID id;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int credits;

    @Column(name = "suggested_semester", nullable = false)
    private int suggestedSemester;

    @Column(nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "subject_prerequisites", joinColumns = @JoinColumn(name = "subject_id"))
    @Column(name = "prerequisite_id", nullable = false)
    private Set<UUID> prerequisiteIds = new HashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static Subject create(UUID programId, String code, String name, int credits, int suggestedSemester,
                                 Instant now) {
        validate(credits, suggestedSemester);
        Subject s = new Subject();
        s.id = UuidV7.newId();
        s.programId = programId;
        s.code = code;
        s.name = name;
        s.credits = credits;
        s.suggestedSemester = suggestedSemester;
        s.active = true;
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    /** @return {@code true} si algo cambió (para importaciones idempotentes) */
    public boolean update(String name, int credits, int suggestedSemester, boolean active, Instant now) {
        validate(credits, suggestedSemester);
        boolean changed = !Objects.equals(this.name, name) || this.credits != credits
                || this.suggestedSemester != suggestedSemester || this.active != active;
        if (changed) {
            this.name = name;
            this.credits = credits;
            this.suggestedSemester = suggestedSemester;
            this.active = active;
            this.updatedAt = now;
        }
        return changed;
    }

    /** @return {@code true} si la lista de prerrequisitos cambió */
    public boolean replacePrerequisites(Set<UUID> prerequisites, Instant now) {
        if (prerequisites.contains(id)) {
            throw DomainException.badRequest("Una materia no puede ser prerrequisito de sí misma");
        }
        if (prerequisiteIds.equals(prerequisites)) {
            return false;
        }
        prerequisiteIds.clear();
        prerequisiteIds.addAll(prerequisites);
        updatedAt = now;
        return true;
    }

    private static void validate(int credits, int suggestedSemester) {
        if (credits < 0 || credits > 30) {
            throw DomainException.badRequest("Los créditos deben estar entre 0 y 30");
        }
        if (suggestedSemester < 1 || suggestedSemester > 14) {
            throw DomainException.badRequest("El semestre sugerido debe estar entre 1 y 14");
        }
    }
}

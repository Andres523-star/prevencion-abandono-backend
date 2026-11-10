package co.edu.retencion.students.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
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

import org.hibernate.annotations.SQLRestriction;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.students.StudentStatus;

/**
 * Estudiante. No tiene datos personales: se identifica por {@code anonCode}; la identidad vive en
 * {@link StudentIdentity}. Borrado lógico (nunca físico sin proceso formal de supresión).
 */
@Entity
@Table(name = "students")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Student {

    @Id
    private UUID id;

    @Column(name = "anon_code", nullable = false, unique = true)
    private String anonCode;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(name = "current_semester", nullable = false)
    private int currentSemester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StudentStatus status;

    @Column(name = "admission_date", nullable = false)
    private LocalDate admissionDate;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    private long version;

    public static Student register(String anonCode, UUID programId, int semester, StudentStatus status,
                                   LocalDate admissionDate, Instant now) {
        validate(semester);
        Student s = new Student();
        s.id = UuidV7.newId();
        s.anonCode = anonCode;
        s.programId = programId;
        s.currentSemester = semester;
        s.status = status;
        s.admissionDate = admissionDate;
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    /** @return {@code true} si algo cambió */
    public boolean updateAcademic(UUID programId, int semester, StudentStatus status, LocalDate admissionDate,
                                  Instant now) {
        validate(semester);
        boolean changed = !programId.equals(this.programId) || semester != currentSemester
                || status != this.status || !admissionDate.equals(this.admissionDate);
        if (changed) {
            this.programId = programId;
            this.currentSemester = semester;
            this.status = status;
            this.admissionDate = admissionDate;
            this.updatedAt = now;
        }
        return changed;
    }

    /** Vincula la cuenta de usuario del portal. Una cuenta solo puede vincularse a un estudiante. */
    public void linkUser(UUID user, Instant now) {
        if (userId != null && !userId.equals(user)) {
            throw DomainException.conflict("El estudiante ya está vinculado a otra cuenta");
        }
        this.userId = user;
        this.updatedAt = now;
    }

    public boolean isLinkedTo(UUID user) {
        return Objects.equals(userId, user);
    }

    /** Quita el vínculo con el usuario (supresión de datos del titular). */
    public void unlinkUser(Instant now) {
        this.userId = null;
        this.updatedAt = now;
    }

    public void softDelete(Instant now) {
        this.deletedAt = now;
        this.updatedAt = now;
    }

    private static void validate(int semester) {
        if (semester < 1 || semester > 14) {
            throw DomainException.badRequest("El semestre debe estar entre 1 y 14");
        }
    }
}

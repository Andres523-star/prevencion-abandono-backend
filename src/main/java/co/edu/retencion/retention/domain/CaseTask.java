package co.edu.retencion.retention.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/** Tarea del plan de apoyo del caso, con fecha. */
@Entity
@Table(name = "retention_tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaseTask {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(nullable = false)
    private String title;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;


    public static CaseTask of(UUID caseId, String title, LocalDate dueDate, UUID actor, Instant now) {
        if (title == null || title.isBlank() || title.length() > 200 || dueDate == null) {
            throw DomainException.badRequest("La tarea necesita título (máx. 200) y fecha");
        }
        CaseTask t = new CaseTask();
        t.id = UuidV7.newId();
        t.caseId = caseId;
        t.title = title.strip();
        t.dueDate = dueDate;
        t.createdBy = actor;
        t.createdAt = now;
        return t;
    }

    public void done(Instant now) {
        if (doneAt == null) {
            this.doneAt = now;
        }
    }

}

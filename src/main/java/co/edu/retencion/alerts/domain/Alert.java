package co.edu.retencion.alerts.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.id.UuidV7;

/**
 * Alerta de riesgo de un estudiante. Ciclo: NUEVA → VISTA → ATENDIDA o DESCARTADA (con nota). Si no
 * se atiende a tiempo se escala (sube {@code escalationLevel} y cambia de responsable).
 */
@Entity
@Table(name = "alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Alert {

    /** Estado de la alerta. */
    public enum Status {
        NUEVA,
        VISTA,
        ATENDIDA,
        DESCARTADA;

        public boolean open() {
            return this == NUEVA || this == VISTA;
        }
    }

    /** Origen de la alerta. */
    public enum Kind {
        RIESGO,
        ASISTENCIA,
        PERDIDA
    }

    /** Rol responsable. */
    public enum AssigneeRole {
        CONSEJERO,
        PSICOLOGO,
        COORDINADOR
    }

    @Id
    private UUID id;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "program_id", nullable = false)
    private UUID programId;

    @Column(nullable = false)
    private int semester;

    @Column(name = "group_id")
    private UUID groupId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    @Column(nullable = false)
    private String level;

    @Column(name = "attendance_level")
    private String attendanceLevel;

    private BigDecimal score;

    @Column(name = "score_type")
    private String scoreType;

    @Column(name = "assessment_id")
    private UUID assessmentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String factors;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggested_actions", nullable = false, columnDefinition = "jsonb")
    private String suggestedActions;

    @Column(nullable = false)
    private boolean sensitive;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_role")
    private AssigneeRole assignedRole;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "resolution_note_ciphertext")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /** Datos que llegan del motor de riesgo o de la asistencia. */
    public record Signal(UUID studentId, UUID programId, int semester, UUID groupId, Kind kind, String level,
                         String attendanceLevel, BigDecimal score, String scoreType, UUID assessmentId,
                         String factorsJson, String actionsJson, boolean sensitive) {
    }

    public static String dedupeKey(Signal s) {
        return s.kind() == Kind.RIESGO ? "RIESGO:" + s.studentId()
                : s.kind().name() + ":" + s.studentId() + ":" + s.groupId();
    }

    public static Alert open(Signal s, Instant now) {
        Alert a = new Alert();
        a.id = UuidV7.newId();
        a.studentId = s.studentId();
        a.programId = s.programId();
        a.semester = s.semester();
        a.groupId = s.groupId();
        a.kind = s.kind();
        a.dedupeKey = dedupeKey(s);
        a.status = Status.NUEVA;
        a.createdAt = now;
        a.refresh(s, now);
        return a;
    }

    /**
     * Una señal repetida sobre una alerta abierta actualiza su contenido.
     *
     * @return {@code true} si el nivel subió (amerita volver a avisar)
     */
    public boolean refresh(Signal s, Instant now) {
        boolean raised = level != null && rank(s.level()) > rank(level);
        this.level = s.level();
        this.attendanceLevel = s.attendanceLevel();
        this.score = s.score();
        this.scoreType = s.scoreType();
        this.assessmentId = s.assessmentId();
        this.factors = s.factorsJson() == null ? "[]" : s.factorsJson();
        this.suggestedActions = s.actionsJson() == null ? "[]" : s.actionsJson();
        this.sensitive = this.sensitive || s.sensitive();
        this.updatedAt = now;
        return raised;
    }

    public void assign(UUID user, AssigneeRole role, Instant now) {
        this.assignedTo = user;
        this.assignedRole = role;
        this.updatedAt = now;
    }

    /** El responsable la abrió. */
    public void markSeen(Instant now) {
        if (status == Status.NUEVA) {
            this.status = Status.VISTA;
            this.updatedAt = now;
        }
    }

    public void close(boolean attended, UUID actor, String note, Instant now) {
        if (!status.open()) {
            throw DomainException.conflict("La alerta ya está cerrada");
        }
        if (!attended && (note == null || note.isBlank())) {
            throw DomainException.badRequest("Descartar una alerta exige una nota");
        }
        this.status = attended ? Status.ATENDIDA : Status.DESCARTADA;
        this.resolutionNote = note == null || note.isBlank() ? null : note.trim();
        this.resolvedBy = actor;
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    /** Lleva más de {@code hours} sin atender desde su creación o su último escalamiento. */
    public boolean overdue(Duration hours, Instant now) {
        Instant since = escalatedAt != null ? escalatedAt : createdAt;
        return status.open() && !since.plus(hours).isAfter(now);
    }

    public void escalate(UUID coordinator, Instant now) {
        this.escalationLevel++;
        this.escalatedAt = now;
        if (coordinator != null) {
            assign(coordinator, AssigneeRole.COORDINADOR, now);
        }
        this.updatedAt = now;
    }

    static int rank(String level) {
        return switch (level == null ? "" : level) {
            case "ALTO" -> 3;
            case "MEDIO" -> 2;
            case "BAJO" -> 1;
            default -> 0;
        };
    }
}

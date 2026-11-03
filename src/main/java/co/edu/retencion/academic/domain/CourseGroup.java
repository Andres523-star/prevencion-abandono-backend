package co.edu.retencion.academic.domain;

import co.edu.retencion.common.error.DomainException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.academic.GroupType;
import co.edu.retencion.common.id.UuidV7;

/**
 * Grupo: materia + periodo + docente + cupo, con su horario semanal. Agregado raíz de los
 * horarios (las franjas se reemplazan siempre a través del grupo).
 */
@Entity
@Table(name = "course_groups")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseGroup {

    @Id
    private UUID id;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "term_id", nullable = false)
    private UUID termId;

    @Column(nullable = false)
    private String code;

    @Column(name = "teacher_user_id")
    private UUID teacherUserId;

    @Column(nullable = false)
    private int capacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_type", nullable = false)
    private GroupType groupType;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayOfWeek, startTime")
    private List<ClassSchedule> schedules = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /**
     * Crea un grupo. Una práctica clínica solo se permite en programas clínicos.
     */
    public static CourseGroup create(Subject subject, Program program, UUID termId, String code,
                                     UUID teacherUserId, int capacity, GroupType type, Instant now) {
        validate(program, capacity, type);
        CourseGroup g = new CourseGroup();
        g.id = UuidV7.newId();
        g.subjectId = subject.getId();
        g.termId = termId;
        g.code = code;
        g.teacherUserId = teacherUserId;
        g.capacity = capacity;
        g.groupType = type;
        g.createdAt = now;
        g.updatedAt = now;
        return g;
    }

    /** @return {@code true} si cambió algo */
    public boolean update(Program program, int capacity, GroupType type, Instant now) {
        validate(program, capacity, type);
        boolean changed = this.capacity != capacity || this.groupType != type;
        if (changed) {
            this.capacity = capacity;
            this.groupType = type;
            this.updatedAt = now;
        }
        return changed;
    }

    /** Cambia el docente y devuelve el anterior. */
    public UUID assignTeacher(UUID teacher, Instant now) {
        UUID previous = this.teacherUserId;
        this.teacherUserId = teacher;
        this.updatedAt = now;
        return previous;
    }

    public boolean taughtBy(UUID teacher) {
        return Objects.equals(teacherUserId, teacher);
    }

    /**
     * Reemplaza el horario completo. Rechaza franjas que se solapan entre sí.
     *
     * @return {@code true} si el horario cambió
     */
    public boolean replaceSchedule(List<Slot> slots, Instant now) {
        boolean same = slots.size() == schedules.size() && slots.stream().allMatch(s ->
                schedules.stream().anyMatch(existing -> existing.sameSlot(s.day(), s.start(), s.end(), s.room())));
        if (same) {
            return false;
        }
        List<ClassSchedule> replacement = new ArrayList<>();
        for (Slot slot : slots) {
            ClassSchedule candidate = new ClassSchedule(this, slot.day(), slot.start(), slot.end(), slot.room());
            if (replacement.stream().anyMatch(r -> r.overlaps(slot.day(), slot.start(), slot.end()))) {
                throw DomainException.badRequest("El horario tiene franjas que se solapan (" + slot.day() + ")");
            }
            replacement.add(candidate);
        }
        schedules.clear();
        schedules.addAll(replacement);
        updatedAt = now;
        return true;
    }

    private static void validate(Program program, int capacity, GroupType type) {
        if (capacity < 1 || capacity > 1000) {
            throw DomainException.badRequest("El cupo debe estar entre 1 y 1000");
        }
        if (type == GroupType.PRACTICA_CLINICA && !program.isClinical()) {
            throw DomainException.badRequest("El programa " + program.getCode() + " no admite prácticas clínicas");
        }
    }

    /**
     * Franja de horario solicitada.
     *
     * @param day   día
     * @param start inicio
     * @param end   fin
     * @param room  aula
     */
    public record Slot(DayOfWeek day, LocalTime start, LocalTime end, String room) {
    }
}

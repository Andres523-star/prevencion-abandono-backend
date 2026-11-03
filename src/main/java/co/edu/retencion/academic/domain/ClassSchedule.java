package co.edu.retencion.academic.domain;

import co.edu.retencion.common.error.DomainException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/** Franja semanal de clase de un grupo (día, hora inicio/fin y aula). */
@Entity
@Table(name = "class_schedules")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassSchedule {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id")
    private CourseGroup group;

    /** 1 = lunes ... 7 = domingo (ISO-8601). */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    private String room;

    ClassSchedule(CourseGroup group, DayOfWeek day, LocalTime start, LocalTime end, String room) {
        if (!end.isAfter(start)) {
            throw DomainException.badRequest("La hora de fin debe ser posterior a la de inicio");
        }
        this.id = UuidV7.newId();
        this.group = group;
        this.dayOfWeek = day.getValue();
        this.startTime = start;
        this.endTime = end;
        this.room = room;
    }

    public DayOfWeek day() {
        return DayOfWeek.of(dayOfWeek);
    }

    boolean overlaps(DayOfWeek day, LocalTime start, LocalTime end) {
        return dayOfWeek == day.getValue() && start.isBefore(endTime) && startTime.isBefore(end);
    }

    boolean sameSlot(DayOfWeek day, LocalTime start, LocalTime end, String otherRoom) {
        return dayOfWeek == day.getValue() && startTime.equals(start) && endTime.equals(end)
                && java.util.Objects.equals(room, otherRoom);
    }
}

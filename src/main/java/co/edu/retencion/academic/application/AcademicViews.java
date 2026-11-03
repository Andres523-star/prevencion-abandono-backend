package co.edu.retencion.academic.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import co.edu.retencion.academic.GroupType;
import co.edu.retencion.academic.domain.AttendancePolicy;
import co.edu.retencion.academic.domain.ClassSchedule;
import co.edu.retencion.academic.domain.ClassSession;
import co.edu.retencion.academic.domain.ClassSuspension;
import co.edu.retencion.academic.domain.CourseGroup;
import co.edu.retencion.academic.domain.Holiday;
import co.edu.retencion.academic.domain.Program;
import co.edu.retencion.academic.domain.Subject;
import co.edu.retencion.academic.domain.Term;

/** DTOs de salida del módulo académico (las entidades nunca salen por la API). */
public final class AcademicViews {

    private AcademicViews() {
    }

    public record ProgramView(UUID id, String code, String name, boolean clinical, boolean active) {
        static ProgramView of(Program p) {
            return new ProgramView(p.getId(), p.getCode(), p.getName(), p.isClinical(), p.isActive());
        }
    }

    public record TermView(UUID id, String code, String name, LocalDate startDate, LocalDate endDate,
                           Term.Status status, long version) {
        static TermView of(Term t) {
            return new TermView(t.getId(), t.getCode(), t.getName(), t.getStartDate(), t.getEndDate(), t.getStatus(),
                    t.getVersion());
        }
    }

    public record HolidayView(LocalDate date, String name) {
        static HolidayView of(Holiday h) {
            return new HolidayView(h.getDate(), h.getName());
        }
    }

    /** @param cancelledSessions sesiones programadas que quedaron canceladas por la suspensión */
    public record SuspensionView(UUID id, LocalDate startDate, LocalDate endDate, UUID programId, String reason,
                                 Instant createdAt, int cancelledSessions) {
        static SuspensionView of(ClassSuspension s, int cancelled) {
            return new SuspensionView(s.getId(), s.getStartDate(), s.getEndDate(), s.getProgramId(), s.getReason(),
                    s.getCreatedAt(), cancelled);
        }
    }

    public record SubjectView(UUID id, UUID programId, String code, String name, int credits, int suggestedSemester,
                              boolean active, Set<UUID> prerequisiteIds, long version) {
        static SubjectView of(Subject s) {
            return new SubjectView(s.getId(), s.getProgramId(), s.getCode(), s.getName(), s.getCredits(),
                    s.getSuggestedSemester(), s.isActive(), Set.copyOf(s.getPrerequisiteIds()), s.getVersion());
        }
    }

    public record SlotView(DayOfWeek day, LocalTime start, LocalTime end, String room) {
        static SlotView of(ClassSchedule c) {
            return new SlotView(c.day(), c.getStartTime(), c.getEndTime(), c.getRoom());
        }
    }

    public record GroupView(UUID id, UUID subjectId, UUID programId, UUID termId, String code, UUID teacherUserId,
                            int capacity, GroupType groupType, List<SlotView> schedule, long version) {
        static GroupView of(CourseGroup g, UUID programId) {
            return new GroupView(g.getId(), g.getSubjectId(), programId, g.getTermId(), g.getCode(),
                    g.getTeacherUserId(), g.getCapacity(), g.getGroupType(),
                    g.getSchedules().stream().map(SlotView::of).toList(), g.getVersion());
        }
    }

    public record SessionView(UUID id, UUID groupId, LocalDate date, LocalTime start, LocalTime end, String room,
                              ClassSession.Status status, String cancellationReason) {
        static SessionView of(ClassSession s) {
            return new SessionView(s.getId(), s.getGroupId(), s.getDate(), s.getStartTime(), s.getEndTime(),
                    s.getRoom(), s.getStatus(), s.getCancellationReason());
        }
    }

    /** @param created sesiones nuevas (las existentes no se duplican) */
    public record GenerationResult(int created) {
    }

    public record PolicyView(AttendancePolicy.Scope scope, UUID programId, UUID subjectId, int maxAbsences,
                             int warningThreshold, int criticalThreshold, int justificationWindowBusinessDays,
                             Integer tardiesPerAbsence, Instant updatedAt, long version) {
        static PolicyView of(AttendancePolicy p) {
            return new PolicyView(p.getScope(), p.getProgramId(), p.getSubjectId(), p.getMaxAbsences(),
                    p.getWarningThreshold(), p.getCriticalThreshold(), p.getJustificationWindowDays(),
                    p.getTardiesPerAbsence(), p.getUpdatedAt(), p.getVersion());
        }
    }
}

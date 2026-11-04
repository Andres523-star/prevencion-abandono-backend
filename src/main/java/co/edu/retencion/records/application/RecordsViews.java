package co.edu.retencion.records.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.AttendanceStatus;
import co.edu.retencion.records.JustificationState;
import co.edu.retencion.records.domain.AbsenceCounter;
import co.edu.retencion.records.domain.AttendanceRecord;
import co.edu.retencion.records.domain.DropoutCause;
import co.edu.retencion.records.domain.Grade;
import co.edu.retencion.records.domain.GradeIndicators;
import co.edu.retencion.records.domain.Observation;
import co.edu.retencion.records.domain.StudentCause;

/** DTOs del registro académico. Ninguno lleva datos personales del estudiante. */
public final class RecordsViews {

    private RecordsViews() {
    }

    public record AttendanceView(UUID recordId, UUID sessionId, UUID enrollmentId, UUID studentId, UUID groupId,
                                 AttendanceStatus status, JustificationState justification,
                                 LocalDate justificationDeadline, Instant updatedAt) {
        static AttendanceView of(AttendanceRecord r) {
            return new AttendanceView(r.getId(), r.getSessionId(), r.getEnrollmentId(), r.getStudentId(), r.getGroupId(),
                    r.getStatus(), r.getJustification(), r.getJustificationDeadline(), r.getUpdatedAt());
        }
    }

    public record CounterView(UUID enrollmentId, UUID studentId, UUID groupId, int unjustified, int pending,
                              int justified, int tardies, int countedAbsences, int maxAbsences, AttendanceLevel level) {
        static CounterView of(AbsenceCounter c) {
            return new CounterView(c.getEnrollmentId(), c.getStudentId(), c.getGroupId(), c.getUnjustified(),
                    c.getPending(), c.getJustified(), c.getTardies(), c.getCountedAbsences(), c.getMaxAbsences(),
                    c.getLevel());
        }
    }

    public record GradeView(UUID id, UUID enrollmentId, UUID studentId, UUID groupId, BigDecimal value,
                            LocalDate assessedOn, String description) {
        static GradeView of(Grade g) {
            return new GradeView(g.getId(), g.getEnrollmentId(), g.getStudentId(), g.getGroupId(), g.getValue(),
                    g.getAssessedOn(), g.getDescription());
        }
    }

    public record SubjectIndicatorView(UUID groupId, String subjectName, BigDecimal average, int gradeCount,
                                       boolean belowPassing, GradeIndicators.Trend trend, BigDecimal lastGrade) {
    }

    /**
     * @param passingGrade nota de aprobación (3.0); la plataforma no calcula la nota oficial
     */
    public record IndicatorsView(UUID studentId, UUID termId, BigDecimal semesterAverage, int subjectsBelowPassing,
                                 BigDecimal passingGrade, List<SubjectIndicatorView> subjects) {
    }

    public record ObservationView(UUID id, UUID studentId, UUID groupId, UUID authorUserId, Observation.Type type,
                                  String text, Instant createdAt) {
        static ObservationView of(Observation o) {
            return new ObservationView(o.getId(), o.getStudentId(), o.getGroupId(), o.getAuthorUserId(), o.getType(),
                    o.getText(), o.getCreatedAt());
        }
    }

    public record CauseView(String code, String name, DropoutCause.Category category, boolean active) {
        static CauseView of(DropoutCause c) {
            return new CauseView(c.getCode(), c.getName(), c.getCategory(), c.isActive());
        }
    }

    public record StudentCauseView(UUID id, UUID studentId, String causeCode, String causeName,
                                   DropoutCause.Category category, StudentCause.Source source, String notes,
                                   Instant recordedAt, Instant resolvedAt) {
        static StudentCauseView of(StudentCause s, DropoutCause cause) {
            return new StudentCauseView(s.getId(), s.getStudentId(), s.getCauseCode(), cause.getName(),
                    cause.getCategory(), s.getSource(), s.getNotes(), s.getRecordedAt(), s.getResolvedAt());
        }
    }
}

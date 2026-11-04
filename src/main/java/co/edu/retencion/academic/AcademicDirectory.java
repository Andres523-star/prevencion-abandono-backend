package co.edu.retencion.academic;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Consultas públicas de la estructura académica para otros módulos. */
public interface AcademicDirectory {

    Optional<ProgramInfo> findProgram(UUID programId);

    Optional<ProgramInfo> findProgramByCode(String code);

    Optional<TermInfo> findTermByCode(String code);

    Optional<UUID> findSubjectId(UUID programId, String subjectCode);

    Optional<GroupInfo> findGroup(UUID groupId);

    Optional<GroupInfo> findGroup(UUID subjectId, UUID termId, String groupCode);

    /** Grupos que dicta el docente en periodos no cerrados. */
    Set<UUID> groupsTaughtBy(UUID teacherUserId);

    EffectiveAttendancePolicy effectivePolicy(UUID groupId);

    Optional<SessionInfo> findSession(UUID sessionId);

    Optional<SubjectInfo> findSubject(UUID subjectId);

    /** Festivos en el rango (para calcular días hábiles). */
    Set<LocalDate> holidays(LocalDate from, LocalDate to);
}

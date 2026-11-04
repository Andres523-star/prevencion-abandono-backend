package co.edu.retencion.academic;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Consultas públicas de la estructura académica para otros módulos. */
public interface AcademicDirectory {

    Optional<ProgramInfo> findProgram(UUID programId);

    Optional<ProgramInfo> findProgramByCode(String code);

    /** Los programas, ordenados por código (particiones del lote nocturno de riesgo). */
    List<ProgramInfo> listPrograms();

    Optional<TermInfo> findTermByCode(String code);

    Optional<TermInfo> findTerm(UUID termId);

    /** Todos los periodos, del más antiguo al más reciente (exportación del dataset de entrenamiento). */
    List<TermInfo> listTerms();

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

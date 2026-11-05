package co.edu.retencion.chat.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.chat.domain.Conversation;
import co.edu.retencion.chat.domain.ConversationRepository;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.records.RecordsSnapshot;
import co.edu.retencion.records.StudentRecords;
import co.edu.retencion.risk.RiskQueries;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Vista dividida del chat (perfil + gráficas a la izquierda, conversación a la derecha). Aplica el
 * mínimo necesario del docente: solo datos básicos, las materias que comparte con el estudiante,
 * sus faltas y notas en esas materias y la evolución del nivel de riesgo. Nunca factores de
 * bienestar, salud ni socioeconómicos.
 */
@Service
public class StudentContextService {

    private final ConversationRepository conversations;
    private final ChatAccess access;
    private final StudentRegistry students;
    private final StudentRecords records;
    private final RiskQueries risk;
    private final AcademicDirectory academic;

    public StudentContextService(ConversationRepository conversations, ChatAccess access, StudentRegistry students,
                                 StudentRecords records, RiskQueries risk, AcademicDirectory academic) {
        this.conversations = conversations;
        this.access = access;
        this.students = students;
        this.records = records;
        this.risk = risk;
        this.academic = academic;
    }

    public record StudentContext(Profile profile, List<SharedSubject> sharedSubjects, Series series) {
    }

    /** Datos básicos permitidos para el docente (el nombre es lectura auditada). */
    public record Profile(UUID studentId, String anonCode, String fullName, String programCode, int semester) {

        @Override
        public String toString() {
            return "Profile[" + anonCode + "]";
        }
    }

    public record SharedSubject(UUID groupId, String subjectName, BigDecimal average, int gradeCount, String trend,
                                int countedAbsences, int maxAbsences, String attendanceLevel) {
    }

    public record Series(List<RiskPoint> risk, List<StudentRecords.WeeklyAbsences> absencesByWeek,
                         List<GradePoint> gradesBySubject) {
    }

    public record RiskPoint(Instant at, String level, BigDecimal score, String scoreType) {
    }

    public record GradePoint(UUID groupId, String subjectName, BigDecimal value, LocalDate assessedOn) {
    }

    @Transactional(readOnly = true)
    public StudentContext context(AuthenticatedUser actor, UUID conversationId) {
        Conversation c = conversations.findById(conversationId)
                .orElseThrow(() -> DomainException.notFound("Conversación no encontrada"));
        boolean teacher = actor.userId().equals(c.getOwnerUserId()) && actor.hasRole(Role.DOCENTE);
        if (!teacher && !actor.hasRole(Role.ADMIN)) {
            throw new AccessDeniedException("La vista del estudiante es solo para el docente de la conversación");
        }
        StudentInfo student = students.findById(c.getStudentId())
                .orElseThrow(() -> DomainException.notFound("Estudiante no encontrado"));
        Set<UUID> shared = access.sharedGroups(c.getOwnerUserId(), c.getStudentId());
        RecordsSnapshot snapshot = records.snapshot(c.getStudentId(), null);
        List<SharedSubject> subjects = snapshot.subjects().stream().filter(s -> shared.contains(s.groupId()))
                .map(s -> new SharedSubject(s.groupId(), s.subjectName(), s.average(), s.gradeCount(), s.trend(),
                        s.countedAbsences(), s.maxAbsences(), s.attendanceLevel().name())).toList();
        java.util.Map<UUID, String> names = new java.util.HashMap<>();
        snapshot.subjects().forEach(s -> names.put(s.groupId(), s.subjectName()));
        List<GradePoint> grades = records.grades(c.getStudentId(), shared).stream()
                .map(g -> new GradePoint(g.groupId(), names.get(g.groupId()), g.value(), g.assessedOn())).toList();
        List<RiskPoint> riskSeries = risk.history(c.getStudentId(), 30).stream()
                .map(r -> new RiskPoint(r.assessedAt(), r.level().name(), r.score(), r.scoreType().name()))
                .sorted(java.util.Comparator.comparing(RiskPoint::at)).toList();
        String program = academic.findProgram(student.programId()).map(ProgramInfo::code).orElse(null);
        return new StudentContext(new Profile(student.id(), student.anonCode(),
                students.displayName(student.id()).orElse(null), program, student.currentSemester()),
                subjects, new Series(riskSeries, records.weeklyAbsences(c.getStudentId(), shared), grades));
    }
}

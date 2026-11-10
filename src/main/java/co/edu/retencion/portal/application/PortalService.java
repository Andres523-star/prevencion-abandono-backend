package co.edu.retencion.portal.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.EffectiveAttendancePolicy;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.ai.ResourceCatalog;
import co.edu.retencion.ai.SupportResourceInfo;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.records.AbsenceInfo;
import co.edu.retencion.records.RecordsSnapshot;
import co.edu.retencion.records.StudentRecords;
import co.edu.retencion.risk.SupportPlans;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.users.UserDirectory;

/**
 * Vistas del portal. Cada dato sale de la API pública de su módulo; aquí solo se compone, se traduce a
 * lenguaje para el estudiante y se quita lo que no le corresponde ver.
 */
@Service
public class PortalService {

    private final StudentRegistry students;
    private final StudentRecords records;
    private final AcademicDirectory academic;
    private final UserDirectory users;
    private final SupportPlans plans;
    private final ResourceCatalog resources;

    public PortalService(StudentRegistry students, StudentRecords records, AcademicDirectory academic, UserDirectory users,
                         SupportPlans plans, ResourceCatalog resources) {
        this.students = students;
        this.records = records;
        this.academic = academic;
        this.users = users;
        this.plans = plans;
        this.resources = resources;
    }

    /** Política de asistencia en palabras del estudiante. */
    public record AttendanceRule(int maxAbsences, int warningAt, int criticalAt, int justificationBusinessDays,
                                 Integer tardiesPerAbsence, boolean countsPerSession) {
    }

    /** Estado de faltas de una materia, sin etiquetas de riesgo: el portal muestra cifras y cuánto le queda. */
    public record AbsenceStatus(int counted, int unjustified, int pending, int tardies, int remainingBeforeLimit,
                                String state) {
    }

    public record GradeItem(BigDecimal value, LocalDate assessedOn) {
    }

    public record Course(UUID enrollmentId, UUID groupId, String subjectName, String teacherName, String appointmentLink,
                         AbsenceStatus absences, AttendanceRule rule, BigDecimal average, String trend,
                         List<GradeItem> grades) {
    }

    public record Overview(String anonCode, String programName, int semester, BigDecimal semesterAverage,
                           List<Course> courses) {
    }

    public record AbsenceItem(UUID recordId, UUID groupId, String subjectName, LocalDate date, String type,
                              String justification, LocalDate justifyBefore, boolean canJustify) {
    }

    public record Resource(String id, String name, String description, String contact) {
    }

    public record SupportItem(String subjectName, String message, List<Resource> resources) {
    }

    /** @param message texto de acompañamiento; nunca nivel, puntaje ni probabilidad */
    public record Support(String message, List<SupportItem> plans, List<Resource> services) {
    }

    @Transactional(readOnly = true)
    public Overview overview(AuthenticatedUser user) {
        StudentInfo student = me(user);
        RecordsSnapshot snapshot = records.snapshot(student.id(), null);
        Map<UUID, List<GradeItem>> grades = new HashMap<>();
        Set<UUID> groups = snapshot.subjects().stream().map(RecordsSnapshot.SubjectSnapshot::groupId).collect(Collectors.toSet());
        records.grades(student.id(), groups).forEach(g -> grades.computeIfAbsent(g.groupId(), k -> new ArrayList<>())
                .add(new GradeItem(g.value(), g.assessedOn())));
        List<Course> courses = new ArrayList<>();
        for (RecordsSnapshot.SubjectSnapshot s : snapshot.subjects()) {
            Optional<UUID> teacher = academic.findGroup(s.groupId()).map(GroupInfo::teacherUserId);
            EffectiveAttendancePolicy policy = academic.effectivePolicy(s.groupId());
            AttendanceRule rule = new AttendanceRule(policy.maxAbsences(), policy.warningThreshold(),
                    policy.criticalThreshold(), policy.justificationWindowBusinessDays(), policy.tardiesPerAbsence(),
                    policy.countsPerSession());
            AbsenceStatus absences = new AbsenceStatus(s.countedAbsences(), s.unjustified(), s.pending(), s.tardies(),
                    Math.max(0, s.maxAbsences() - s.countedAbsences()), attendanceState(s));
            courses.add(new Course(s.enrollmentId(), s.groupId(), s.subjectName(),
                    teacher.flatMap(users::displayName).orElse(null), teacher.flatMap(users::appointmentLink).orElse(null),
                    absences, rule, s.average(), trendText(s.trend()), grades.getOrDefault(s.groupId(), List.of())));
        }
        String program = academic.findProgram(student.programId()).map(ProgramInfo::name).orElse(null);
        return new Overview(student.anonCode(), program, student.currentSemester(), snapshot.semesterAverage(), courses);
    }

    @Transactional(readOnly = true)
    public List<AbsenceItem> absences(AuthenticatedUser user) {
        StudentInfo student = me(user);
        Map<UUID, String> subjects = new HashMap<>();
        LocalDate today = LocalDate.now(co.edu.retencion.common.time.TimeConfig.BUSINESS_ZONE);
        List<AbsenceItem> items = new ArrayList<>();
        for (AbsenceInfo a : records.absences(student.id())) {
            String subject = subjects.computeIfAbsent(a.groupId(), id -> academic.findGroup(id)
                    .flatMap(g -> academic.findSubject(g.subjectId())).map(x -> x.name()).orElse(null));
            boolean canJustify = a.isAbsence() && a.justification() == co.edu.retencion.records.JustificationState.NINGUNA
                    && a.justificationDeadline() != null && !today.isAfter(a.justificationDeadline());
            items.add(new AbsenceItem(a.recordId(), a.groupId(), subject, a.sessionDate(), a.status().name(),
                    a.justification().name(), a.justificationDeadline(), canJustify));
        }
        return items;
    }

    /**
     * Apoyo para el estudiante: los planes que una persona aprobó para él y los servicios de la universidad a
     * los que puede acudir. Lo generado automáticamente sin revisión humana nunca llega aquí.
     */
    @Transactional(readOnly = true)
    public Support support(AuthenticatedUser user) {
        StudentInfo student = me(user);
        List<SupportItem> items = new ArrayList<>();
        for (SupportPlans.StudentPlan plan : plans.approvedForStudent(student.id())) {
            List<Resource> linked = plan.resourceIds().stream().map(resources::find).flatMap(Optional::stream)
                    .map(PortalService::resource).toList();
            items.add(new SupportItem(plan.subjectName(), plan.message(), linked));
        }
        String programCode = academic.findProgram(student.programId()).map(ProgramInfo::code).orElse(null);
        Map<String, Resource> services = new LinkedHashMap<>();
        resources.eligible(new ResourceCatalog.EligibilityProfile(programCode, student.currentSemester(), null, null, null))
                .forEach(r -> services.putIfAbsent(r.id(), resource(r)));
        String message = items.isEmpty()
                ? "Estos son los servicios de acompañamiento de la universidad. Puedes acudir a ellos cuando lo necesites, "
                        + "y si quieres conversar con alguien, usa la opción \"Necesito ayuda\"."
                : "Tu equipo de acompañamiento preparó estas propuestas para ti. Si tienes dudas, escríbele a tu docente "
                        + "o usa la opción \"Necesito ayuda\".";
        return new Support(message, items, List.copyOf(services.values()));
    }

    private static Resource resource(SupportResourceInfo r) {
        return new Resource(r.id(), r.name(), r.description(), r.contact());
    }

    /** Cifras en palabras: "Vas bien", "Te quedan N faltas"... sin la palabra riesgo. */
    private static String attendanceState(RecordsSnapshot.SubjectSnapshot s) {
        int remaining = s.maxAbsences() - s.countedAbsences();
        if (remaining <= 0) {
            return "Alcanzaste el límite de faltas de esta materia: habla con tu docente o con consejería";
        }
        if (s.countedAbsences() >= s.criticalThreshold()) {
            return remaining == 1 ? "Te queda 1 falta antes del límite" : "Te quedan " + remaining + " faltas antes del límite";
        }
        if (s.countedAbsences() >= s.warningThreshold()) {
            return "Tienes " + s.countedAbsences() + " faltas; el límite es " + s.maxAbsences();
        }
        return "Vas bien con tu asistencia";
    }

    private static String trendText(String trend) {
        if (trend == null) {
            return null;
        }
        return switch (trend) {
            case "SUBE" -> "Tus notas vienen mejorando";
            case "BAJA" -> "Tus últimas notas bajaron";
            default -> "Tus notas se mantienen";
        };
    }

    private StudentInfo me(AuthenticatedUser user) {
        UUID id = students.findStudentIdByUser(user.userId())
                .orElseThrow(() -> new AccessDeniedException("Tu usuario no está vinculado a un estudiante"));
        return students.findById(id).orElseThrow(() -> new AccessDeniedException("Estudiante no disponible"));
    }
}

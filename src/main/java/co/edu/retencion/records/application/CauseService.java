package co.edu.retencion.records.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.records.application.RecordsViews.CauseView;
import co.edu.retencion.records.application.RecordsViews.StudentCauseView;
import co.edu.retencion.records.domain.DropoutCause;
import co.edu.retencion.records.domain.DropoutCauseRepository;
import co.edu.retencion.records.domain.StudentCause;
import co.edu.retencion.records.domain.StudentCauseRepository;

/**
 * Catálogo de causas de riesgo/retiro y su asociación con estudiantes. Las causas de SALUD son
 * datos sensibles: solo las ven PSICOLOGO y ADMIN.
 */
@Service
public class CauseService {

    private final DropoutCauseRepository causes;
    private final StudentCauseRepository studentCauses;
    private final Clock clock;

    public CauseService(DropoutCauseRepository causes, StudentCauseRepository studentCauses, Clock clock) {
        this.causes = causes;
        this.studentCauses = studentCauses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CauseView> catalog() {
        return causes.findAllByOrderByCategoryAscNameAsc().stream().map(CauseView::of).toList();
    }

    @Transactional
    public CauseView upsertCause(String code, String name, DropoutCause.Category category, boolean active) {
        DropoutCause cause = causes.findById(code).map(c -> {
            c.update(name, category, active);
            return c;
        }).orElseGet(() -> new DropoutCause(code, name, category));
        return CauseView.of(causes.saveAndFlush(cause));
    }

    @Transactional
    public StudentCauseView open(UUID studentId, String causeCode, StudentCause.Source source, String notes) {
        DropoutCause cause = causes.findById(causeCode).filter(DropoutCause::isActive)
                .orElseThrow(() -> DomainException.badRequest("Causa inexistente o inactiva"));
        requireCanSee(cause, CurrentUser.require());
        if (studentCauses.findByStudentIdAndCauseCodeAndResolvedAtIsNull(studentId, causeCode).isPresent()) {
            throw DomainException.conflict("El estudiante ya tiene esa causa abierta");
        }
        StudentCause created = studentCauses.save(StudentCause.open(studentId, causeCode, source, notes,
                CurrentUser.require().userId(), clock.instant()));
        return StudentCauseView.of(created, cause);
    }

    @Transactional(readOnly = true)
    public List<StudentCauseView> ofStudent(UUID studentId) {
        AuthenticatedUser user = CurrentUser.require();
        Map<String, DropoutCause> catalog = causes.findAll().stream()
                .collect(Collectors.toMap(DropoutCause::getCode, Function.identity()));
        return studentCauses.findByStudentIdOrderByRecordedAtDesc(studentId).stream()
                .filter(s -> canSee(catalog.get(s.getCauseCode()), user))
                .map(s -> StudentCauseView.of(s, catalog.get(s.getCauseCode()))).toList();
    }

    @Transactional
    public StudentCauseView resolve(UUID studentCauseId) {
        StudentCause cause = studentCauses.findById(studentCauseId)
                .orElseThrow(() -> DomainException.notFound("Causa no encontrada"));
        DropoutCause catalogEntry = causes.findById(cause.getCauseCode()).orElseThrow();
        requireCanSee(catalogEntry, CurrentUser.require());
        cause.resolve(clock.instant());
        return StudentCauseView.of(studentCauses.saveAndFlush(cause), catalogEntry);
    }

    private static boolean canSee(DropoutCause cause, AuthenticatedUser user) {
        return cause.getCategory() != DropoutCause.Category.SALUD || user.hasRole(Role.PSICOLOGO) || user.hasRole(Role.ADMIN);
    }

    private static void requireCanSee(DropoutCause cause, AuthenticatedUser user) {
        if (!canSee(cause, user)) {
            throw new org.springframework.security.access.AccessDeniedException("Causa de salud restringida a bienestar");
        }
    }
}

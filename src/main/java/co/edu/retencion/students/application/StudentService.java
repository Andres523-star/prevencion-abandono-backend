package co.edu.retencion.students.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.ScopeAuthorizationService;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.students.StudentRegistry.StudentRecord;
import co.edu.retencion.students.StudentStatus;
import co.edu.retencion.students.TransportMode;
import co.edu.retencion.students.application.StudentViews.SocioeconomicView;
import co.edu.retencion.students.application.StudentViews.StudentIdentityView;
import co.edu.retencion.students.application.StudentViews.StudentSelfView;
import co.edu.retencion.students.application.StudentViews.StudentView;
import co.edu.retencion.students.domain.SocioeconomicProfile;
import co.edu.retencion.students.domain.SocioeconomicProfileRepository;
import co.edu.retencion.students.domain.Student;
import co.edu.retencion.students.domain.StudentIdentity;
import co.edu.retencion.students.domain.StudentIdentityRepository;
import co.edu.retencion.students.domain.StudentRepository;
import co.edu.retencion.users.UserAccount;
import co.edu.retencion.users.UserDirectory;

/**
 * Casos de uso de estudiantes. La autorización por rol y alcance se declara en el controlador;
 * aquí se aplica el filtrado por alcance de los listados y búsquedas.
 */
@Service
public class StudentService {

    private static final UUID NONE = new UUID(0, 0);

    private final StudentRepository students;
    private final StudentIdentityRepository identities;
    private final SocioeconomicProfileRepository profiles;
    private final StudentWriter writer;
    private final ScopeAuthorizationService abac;
    private final UserDirectory users;
    private final Clock clock;

    public StudentService(StudentRepository students, StudentIdentityRepository identities,
                          SocioeconomicProfileRepository profiles, StudentWriter writer,
                          ScopeAuthorizationService abac, UserDirectory users, Clock clock) {
        this.students = students;
        this.identities = identities;
        this.profiles = profiles;
        this.writer = writer;
        this.abac = abac;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public StudentView create(StudentRecord record) {
        return StudentView.of(writer.upsert(record, CurrentUser.require().userId(), true).student());
    }

    @Transactional(readOnly = true)
    public StudentView get(UUID id) {
        return StudentView.of(load(id));
    }

    /** Portal: el estudiante ve su propio registro e identidad (sin motivo, es su dato). */
    @Transactional(readOnly = true)
    public StudentSelfView me() {
        Student student = students.findByUserId(CurrentUser.require().userId())
                .orElseThrow(() -> DomainException.notFound("Tu cuenta no está vinculada a un estudiante"));
        return new StudentSelfView(StudentView.of(student), StudentIdentityView.of(student, loadIdentity(student.getId())));
    }

    @Transactional(readOnly = true)
    public StudentIdentityView identity(UUID id) {
        Student student = load(id);
        return StudentIdentityView.of(student, loadIdentity(id));
    }

    /**
     * Listado seudonimizado (sin identidad). ADMIN y PSICOLOGO sin programas ven todo; COORDINADOR,
     * CONSEJERO y PSICOLOGO con programas solo los suyos.
     */
    @Transactional(readOnly = true)
    public CursorPage<StudentView> list(UUID programId, StudentStatus status, UUID after, int size) {
        Set<UUID> allowed = allowedPrograms(CurrentUser.require());
        Set<UUID> filter;
        if (programId != null) {
            if (allowed != null && !allowed.contains(programId)) {
                throw new AccessDeniedException("Programa fuera de alcance");
            }
            filter = Set.of(programId);
        } else {
            filter = allowed;
        }
        List<Student> rows = students.search(filter == null, filter == null || filter.isEmpty() ? Set.of(NONE) : filter,
                status == null, status, after == null, after == null ? NONE : after, Limit.of(size + 1));
        return CursorPage.of(rows.stream().map(StudentView::of).toList(), size, StudentView::id);
    }

    /** Búsqueda por documento con blind index (no descifra la columna). Respeta el alcance. */
    @Transactional(readOnly = true)
    public StudentView findByDocument(String documentNumber) {
        StudentIdentity identity = identities.findByDocumentBlindIndex(writer.documentIndex(documentNumber))
                .orElseThrow(() -> DomainException.notFound("No hay estudiante con ese documento"));
        if (!abac.canAccess("STUDENT", identity.getStudentId(), AccessAction.READ)) {
            // Fuera de alcance se responde igual que "no existe" para no filtrar información.
            throw DomainException.notFound("No hay estudiante con ese documento");
        }
        return get(identity.getStudentId());
    }

    @Transactional
    public StudentView updateAcademic(UUID id, UUID programId, int semester, StudentStatus status,
                                      LocalDate admissionDate) {
        Student student = load(id);
        if (!programId.equals(student.getProgramId())
                && !abac.canAccess("PROGRAM", programId, AccessAction.WRITE)) {
            throw new AccessDeniedException("Programa destino fuera de alcance");
        }
        student.updateAcademic(programId, semester, status, admissionDate, clock.instant());
        return StudentView.of(students.saveAndFlush(student));
    }

    @Transactional
    public StudentIdentityView updateIdentity(UUID id, StudentRecord record) {
        Student student = load(id);
        StudentIdentity identity = loadIdentity(id);
        StudentIdentity.Data data = writer.identityData(record);
        identities.findByDocumentBlindIndex(data.documentBlindIndex())
                .filter(other -> !other.getStudentId().equals(id))
                .ifPresent(other -> {
                    throw DomainException.conflict("Ese documento pertenece a otro estudiante");
                });
        identity.update(data, clock.instant());
        writer.linkUserByEmail(student, data.emailBlindIndex());
        return StudentIdentityView.of(student, identities.saveAndFlush(identity));
    }

    /** Borrado lógico: el registro queda oculto; la supresión definitiva es un proceso formal (Fase 7). */
    @Transactional
    public void delete(UUID id) {
        Student student = load(id);
        student.softDelete(clock.instant());
        students.saveAndFlush(student);
    }

    @Transactional
    public StudentView linkUser(UUID id, UUID userId) {
        UserAccount account = users.findById(userId)
                .orElseThrow(() -> DomainException.notFound("Usuario no encontrado"));
        students.findByUserId(userId).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw DomainException.conflict("Esa cuenta ya está vinculada a otro estudiante");
        });
        Student student = load(id);
        student.linkUser(account.id(), clock.instant());
        users.grantRole(account.id(), Role.ESTUDIANTE, CurrentUser.require().userId());
        return StudentView.of(students.saveAndFlush(student));
    }

    @Transactional(readOnly = true)
    public SocioeconomicView socioeconomic(UUID id) {
        load(id);
        return profiles.findById(id).map(SocioeconomicView::of)
                .orElseThrow(() -> DomainException.notFound("El estudiante no tiene perfil socioeconómico"));
    }

    @Transactional
    public SocioeconomicView updateSocioeconomic(UUID id, Integer stratum, Boolean works, Integer dependents,
                                                 TransportMode transport) {
        load(id);
        SocioeconomicProfile profile = profiles.findById(id)
                .map(p -> {
                    p.update(stratum, works, dependents, transport, clock.instant());
                    return p;
                })
                .orElseGet(() -> SocioeconomicProfile.of(id, stratum, works, dependents, transport, clock.instant()));
        return SocioeconomicView.of(profiles.saveAndFlush(profile));
    }

    /** {@code null} = sin restricción de programa. */
    private static Set<UUID> allowedPrograms(AuthenticatedUser user) {
        if (user.hasRole(Role.ADMIN) || (user.hasRole(Role.PSICOLOGO) && user.programIds().isEmpty())) {
            return null;
        }
        return user.programIds();
    }

    Student load(UUID id) {
        return students.findById(id).orElseThrow(() -> DomainException.notFound("Estudiante no encontrado"));
    }

    private StudentIdentity loadIdentity(UUID id) {
        return identities.findById(id).orElseThrow(() -> DomainException.notFound("Identidad no registrada"));
    }
}

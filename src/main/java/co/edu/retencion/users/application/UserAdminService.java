package co.edu.retencion.users.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.crypto.BlindIndexer;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.reference.ProgramReferences;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.users.AuthType;
import co.edu.retencion.users.UserProvisioned;
import co.edu.retencion.users.UserStatus;
import co.edu.retencion.users.application.UserViews.MeView;
import co.edu.retencion.users.application.UserViews.UserDetailView;
import co.edu.retencion.users.application.UserViews.UserSummaryView;
import co.edu.retencion.users.domain.User;
import co.edu.retencion.users.domain.UserRepository;

/**
 * Casos de uso de administración de usuarios: alta (antes del primer ingreso), consulta, roles y
 * alcance por programa, estado y borrado lógico. La autorización se aplica en el controlador.
 */
@Service
public class UserAdminService {

    /** Roles que solo tienen sentido con al menos un programa asignado. */
    private static final Set<Role> PROGRAM_SCOPED = Set.of(Role.COORDINADOR, Role.CONSEJERO);

    private final UserRepository users;
    private final UserViewMapper mapper;
    private final BlindIndexer blindIndexer;
    private final ProgramReferences programReferences;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public UserAdminService(UserRepository users, UserViewMapper mapper, BlindIndexer blindIndexer,
                            ProgramReferences programReferences, ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.mapper = mapper;
        this.blindIndexer = blindIndexer;
        this.programReferences = programReferences;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeView me() {
        AuthenticatedUser current = CurrentUser.require();
        return mapper.toMe(load(current.userId()), current.mfa());
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummaryView> list(Role role, UUID after, int size) {
        Limit limit = Limit.of(size + 1);
        List<User> rows;
        if (role != null) {
            rows = users.findByRole(role, after == null, after == null ? new UUID(0, 0) : after, limit);
        } else {
            rows = after == null ? users.findAllByOrderByIdAsc(limit) : users.findByIdGreaterThanOrderByIdAsc(after, limit);
        }
        return CursorPage.of(rows.stream().map(mapper::toSummary).toList(), size, UserSummaryView::id);
    }

    @Transactional(readOnly = true)
    public UserDetailView get(UUID id) {
        return mapper.toDetail(load(id));
    }

    /**
     * Crea un usuario antes de su primer ingreso (docente, coordinador, consejero...). Cuando entre
     * con Supabase usando ese correo verificado, la cuenta se vincula automáticamente.
     */
    @Transactional
    public UserDetailView create(String email, String displayName, Set<Role> roles, Set<UUID> programIds) {
        validateRoles(roles, programIds);
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        String index = blindIndexer.index(BlindIndexer.Type.EMAIL, normalized);
        if (users.existsByEmailIndexIncludingDeleted(index)) {
            throw DomainException.conflict("Ya existe un usuario con ese correo");
        }
        Instant now = clock.instant();
        User user = User.preProvision(normalized, index, displayName, now);
        user.replaceRoles(roles, CurrentUser.require().userId(), now);
        user.replacePrograms(programIds, now);
        users.save(user);
        events.publishEvent(new UserProvisioned(user.getId(), AuthType.SUPABASE));
        return mapper.toDetail(user);
    }

    @Transactional
    public UserDetailView updateProfile(UUID id, String displayName) {
        User user = load(id);
        user.rename(displayName, clock.instant());
        return mapper.toDetail(users.saveAndFlush(user));
    }

    @Transactional
    public UserSummaryView updateRoles(UUID id, Set<Role> roles, Set<UUID> programIds) {
        AuthenticatedUser actor = CurrentUser.require();
        if (actor.userId().equals(id) && !roles.contains(Role.ADMIN)) {
            throw new DomainException(UsersProblems.SELF_LOCKOUT, "No puedes quitarte el rol ADMIN");
        }
        validateRoles(roles, programIds);
        User user = load(id);
        user.replaceRoles(roles, actor.userId(), clock.instant());
        user.replacePrograms(programIds, clock.instant());
        return mapper.toSummary(users.saveAndFlush(user));
    }

    @Transactional
    public UserSummaryView updateStatus(UUID id, UserStatus status) {
        if (CurrentUser.require().userId().equals(id) && status == UserStatus.DISABLED) {
            throw new DomainException(UsersProblems.SELF_LOCKOUT, "No puedes desactivar tu propia cuenta");
        }
        User user = load(id);
        user.changeStatus(status, clock.instant());
        if (status == UserStatus.DISABLED) {
            // Cierra también las conexiones en tiempo real del usuario.
            events.publishEvent(new co.edu.retencion.common.security.SessionRevoked(user.getId(), null));
        }
        return mapper.toSummary(users.saveAndFlush(user));
    }

    /** Borrado lógico: deja de aparecer y no puede iniciar ni renovar sesión. */
    @Transactional
    public void delete(UUID id) {
        if (CurrentUser.require().userId().equals(id)) {
            throw new DomainException(UsersProblems.SELF_LOCKOUT, "No puedes eliminar tu propia cuenta");
        }
        User user = load(id);
        user.softDelete(clock.instant());
        users.saveAndFlush(user);
    }

    private void validateRoles(Set<Role> roles, Set<UUID> programIds) {
        if (programIds.isEmpty() && roles.stream().anyMatch(PROGRAM_SCOPED::contains)) {
            throw DomainException.badRequest("Los roles COORDINADOR y CONSEJERO requieren al menos un programa");
        }
        if (!programReferences.missing(programIds).isEmpty()) {
            throw DomainException.badRequest("Alguno de los programas indicados no existe");
        }
    }

    private User load(UUID id) {
        return users.findById(id).orElseThrow(() -> DomainException.notFound("Usuario no encontrado"));
    }
}

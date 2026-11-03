package co.edu.retencion.users.application;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.AuthenticatedUser;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.users.UserStatus;
import co.edu.retencion.users.application.UserViews.MeView;
import co.edu.retencion.users.application.UserViews.UserDetailView;
import co.edu.retencion.users.application.UserViews.UserSummaryView;
import co.edu.retencion.users.domain.User;
import co.edu.retencion.users.domain.UserRepository;

/**
 * Casos de uso de administración de usuarios: consulta, asignación de roles/alcance y estado.
 * La autorización por rol y alcance se aplica en el controlador (anotaciones).
 */
@Service
public class UserAdminService {

    /** Roles que solo tienen sentido con al menos un programa asignado. */
    private static final Set<Role> PROGRAM_SCOPED = Set.of(Role.COORDINADOR, Role.CONSEJERO);

    private final UserRepository users;
    private final UserViewMapper mapper;
    private final Clock clock;

    public UserAdminService(UserRepository users, UserViewMapper mapper, Clock clock) {
        this.users = users;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeView me() {
        AuthenticatedUser current = CurrentUser.require();
        return mapper.toMe(load(current.userId()), current.mfa());
    }

    @Transactional(readOnly = true)
    public CursorPage<UserSummaryView> list(UUID after, int size) {
        Limit limit = Limit.of(size + 1);
        List<User> rows = after == null
                ? users.findAllByOrderByIdAsc(limit)
                : users.findByIdGreaterThanOrderByIdAsc(after, limit);
        return CursorPage.of(rows.stream().map(mapper::toSummary).toList(), size, UserSummaryView::id);
    }

    @Transactional(readOnly = true)
    public UserDetailView get(UUID id) {
        return mapper.toDetail(load(id));
    }

    @Transactional
    public UserSummaryView updateRoles(UUID id, Set<Role> roles, Set<UUID> programIds) {
        AuthenticatedUser actor = CurrentUser.require();
        if (actor.userId().equals(id) && !roles.contains(Role.ADMIN)) {
            throw new DomainException(UsersProblems.SELF_LOCKOUT, "No puedes quitarte el rol ADMIN");
        }
        if (programIds.isEmpty() && roles.stream().anyMatch(PROGRAM_SCOPED::contains)) {
            throw DomainException.badRequest("Los roles COORDINADOR y CONSEJERO requieren al menos un programa");
        }
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
        return mapper.toSummary(users.saveAndFlush(user));
    }

    private User load(UUID id) {
        return users.findById(id).orElseThrow(() -> DomainException.notFound("Usuario no encontrado"));
    }
}

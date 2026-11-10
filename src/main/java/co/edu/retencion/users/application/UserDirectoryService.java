package co.edu.retencion.users.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.common.crypto.BlindIndexer;
import co.edu.retencion.common.data.UpsertOutcome;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.users.AuthType;
import co.edu.retencion.users.FederatedIdentity;
import co.edu.retencion.users.UserAccount;
import co.edu.retencion.users.UserDirectory;
import co.edu.retencion.users.UserProvisioned;
import co.edu.retencion.users.domain.User;
import co.edu.retencion.users.domain.UserRepository;

/**
 * Implementación de la API pública {@link UserDirectory}: aprovisionamiento y vinculación de
 * identidades institucionales.
 */
@Service
public class UserDirectoryService implements UserDirectory {

    private final UserRepository users;
    private final BlindIndexer blindIndexer;
    private final ProvisioningPolicy policy;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public UserDirectoryService(UserRepository users, BlindIndexer blindIndexer, ProvisioningPolicy policy,
                                ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.blindIndexer = blindIndexer;
        this.policy = policy;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<UUID> activeUsersWithRole(Role role, UUID programId) {
        return users.findActiveByRole(role).stream()
                .filter(u -> programId == null || u.getProgramIds().isEmpty() || u.getProgramIds().contains(programId))
                .map(User::getId).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> appointmentLink(UUID userId) {
        return users.findById(userId).map(User::getAppointmentLink);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> displayName(UUID userId) {
        return users.findById(userId).map(co.edu.retencion.users.domain.User::getDisplayName)
                .filter(n -> n != null && !n.isBlank());
    }

    @Override
    @Transactional
    public void deactivate(UUID userId) {
        users.findById(userId).ifPresent(u -> {
            u.changeStatus(co.edu.retencion.users.UserStatus.DISABLED, clock.instant());
            users.save(u);
            events.publishEvent(new co.edu.retencion.common.security.SessionRevoked(userId, null));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UUID userId) {
        return users.findById(userId).map(UserDirectoryService::toAccount);
    }

    @Override
    @Transactional
    public UserAccount linkOrProvision(FederatedIdentity identity) {
        Instant now = clock.instant();
        String emailIndex = blindIndexer.index(BlindIndexer.Type.EMAIL, identity.email());

        // 1) Ya vinculado por su identificador inmutable en Supabase (sub).
        Optional<User> bySubject = users.findByExternalIssuerAndExternalSubject(identity.issuer(), identity.subject());
        if (bySubject.isPresent()) {
            User user = bySubject.get();
            user.refreshProfile(identity.email(), emailIndex, identity.displayName(), now);
            return toAccount(user);
        }

        // 2) Creado previamente por correo (p. ej. importación de docentes) y aún sin vincular.
        Optional<User> byEmail = users.findByEmailBlindIndex(emailIndex);
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            if (user.getExternalSubject() != null) {
                throw new DomainException(UsersProblems.IDENTITY_CONFLICT,
                        "El correo ya está vinculado a otra cuenta");
            }
            user.linkExternal(identity.issuer(), identity.subject(), now);
            user.refreshProfile(identity.email(), emailIndex, identity.displayName(), now);
            return toAccount(user);
        }

        // 3) Primer ingreso: se crea sin roles salvo las reglas de arranque configuradas.
        if (users.existsByEmailIndexIncludingDeleted(emailIndex)) {
            throw new DomainException(UsersProblems.IDENTITY_CONFLICT, "La cuenta con ese correo fue eliminada");
        }
        User user = User.provisionExternal(identity.issuer(), identity.subject(), identity.email(), emailIndex,
                identity.displayName(), now);
        user.replaceRoles(policy.initialRoles(identity.email()), null, now);
        users.save(user);
        events.publishEvent(new UserProvisioned(user.getId(), AuthType.SUPABASE));
        return toAccount(user);
    }

    @Override
    @Transactional
    public UserAccount createLocalAccount(String label, Set<Role> roles) {
        Instant now = clock.instant();
        User user = User.createLocal(label, now);
        user.replaceRoles(roles, null, now);
        users.save(user);
        events.publishEvent(new UserProvisioned(user.getId(), AuthType.LOCAL));
        return toAccount(user);
    }

    @Override
    @Transactional
    public void recordLogin(UUID userId) {
        users.findById(userId).ifPresent(u -> u.recordLogin(clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByEmail(String email) {
        return findIdByEmailIndex(blindIndexer.index(BlindIndexer.Type.EMAIL, email));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByEmailIndex(String emailBlindIndex) {
        return users.findByEmailBlindIndex(emailBlindIndex).map(User::getId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> emailBlindIndexOf(UUID userId) {
        return users.findById(userId).map(User::getEmailBlindIndex);
    }

    @Override
    @Transactional
    public void grantRole(UUID userId, Role role, UUID grantedBy) {
        users.findById(userId).ifPresent(u -> u.addRole(role, grantedBy, clock.instant()));
    }

    @Override
    @Transactional
    public StaffUpsert upsertStaff(String email, String displayName, Role role, UUID grantedBy) {
        Instant now = clock.instant();
        String index = blindIndexer.index(BlindIndexer.Type.EMAIL, email);
        Optional<User> existing = users.findByEmailBlindIndex(index);
        if (existing.isPresent()) {
            User user = existing.get();
            boolean changed = user.addRole(role, grantedBy, now);
            changed |= user.rename(displayName, now);
            return new StaffUpsert(user.getId(), changed ? UpsertOutcome.UPDATED : UpsertOutcome.UNCHANGED);
        }
        if (users.existsByEmailIndexIncludingDeleted(index)) {
            throw new DomainException(UsersProblems.IDENTITY_CONFLICT, "Existe un usuario eliminado con ese correo");
        }
        User user = User.preProvision(email.trim().toLowerCase(java.util.Locale.ROOT), index, displayName, now);
        user.addRole(role, grantedBy, now);
        users.save(user);
        events.publishEvent(new UserProvisioned(user.getId(), AuthType.SUPABASE));
        return new StaffUpsert(user.getId(), UpsertOutcome.CREATED);
    }

    static UserAccount toAccount(User user) {
        return new UserAccount(user.getId(), user.getAuthType(), user.getStatus(), user.roles(), user.getProgramIds());
    }
}

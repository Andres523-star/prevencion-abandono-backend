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

    static UserAccount toAccount(User user) {
        return new UserAccount(user.getId(), user.getAuthType(), user.getStatus(), user.roles(), user.getProgramIds());
    }
}

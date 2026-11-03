package co.edu.retencion.auth.infrastructure;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.auth.domain.LocalCredential;
import co.edu.retencion.auth.domain.LocalCredentialRepository;
import co.edu.retencion.auth.domain.Totp;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.users.UserAccount;
import co.edu.retencion.users.UserDirectory;

/**
 * Crea (o rota) la cuenta break-glass al arrancar, a partir de variables de entorno. Si el hash
 * o el secreto TOTP cambian en el entorno, se actualizan y se reinicia el bloqueo.
 * Nunca registra en logs el usuario, el hash ni el secreto.
 */
@Component
class BreakGlassBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassBootstrap.class);

    private final AuthProperties properties;
    private final LocalCredentialRepository credentials;
    private final UserDirectory users;
    private final Clock clock;

    BreakGlassBootstrap(AuthProperties properties, LocalCredentialRepository credentials, UserDirectory users,
                        Clock clock) {
        this.properties = properties;
        this.credentials = credentials;
        this.users = users;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AuthProperties.BreakGlass config = properties.breakGlass();
        if (!config.enabled() || config.username() == null || config.username().isBlank()) {
            return;
        }
        validate(config);
        String username = config.username().trim().toLowerCase(Locale.ROOT);
        Optional<LocalCredential> existing = credentials.findByUsername(username);
        if (existing.isPresent()) {
            LocalCredential credential = existing.get();
            if (!credential.getPasswordHash().equals(config.passwordHash())
                    || !credential.getTotpSecret().equals(config.totpSecret())) {
                credential.rotateSecrets(config.passwordHash(), config.totpSecret(), clock.instant());
                log.warn("Se rotaron los secretos de la cuenta de emergencia");
            }
            return;
        }
        UserAccount account = users.createLocalAccount("Cuenta de emergencia", Set.of(Role.ADMIN));
        credentials.save(LocalCredential.create(account.id(), username, config.passwordHash(), config.totpSecret(),
                clock.instant()));
        log.warn("Se creó la cuenta de emergencia (break-glass) con rol ADMIN");
    }

    private static void validate(AuthProperties.BreakGlass config) {
        if (config.passwordHash() == null || !config.passwordHash().startsWith("$argon2id$")) {
            throw new IllegalStateException("BREAK_GLASS_PASSWORD_HASH debe ser un hash argon2id");
        }
        if (config.totpSecret() == null || Totp.decodeBase32(config.totpSecret()).length < 20) {
            throw new IllegalStateException("BREAK_GLASS_TOTP_SECRET debe ser Base32 de al menos 160 bits");
        }
    }
}

package co.edu.retencion.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.crypto.EncryptedStringConverter;

/**
 * Credencial de una cuenta break-glass (emergencia, solo ADMIN): hash argon2id de la contraseña,
 * secreto TOTP cifrado y estado de bloqueo progresivo.
 */
@Entity
@Table(name = "local_credentials")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocalCredential {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "totp_secret_ciphertext", nullable = false)
    private String totpSecret;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_totp_step", nullable = false)
    private long lastTotpStep;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static LocalCredential create(UUID userId, String username, String passwordHash, String totpSecret,
                                         Instant now) {
        LocalCredential c = new LocalCredential();
        c.userId = userId;
        c.username = username;
        c.passwordHash = passwordHash;
        c.totpSecret = totpSecret;
        c.updatedAt = now;
        return c;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /** Registra un fallo y aplica el bloqueo progresivo si corresponde. */
    public void registerFailure(Instant now, LockoutPolicy policy) {
        failedAttempts++;
        lockedUntil = policy.lockUntil(failedAttempts, now).orElse(null);
        updatedAt = now;
    }

    /** Ingreso correcto: reinicia contadores y guarda el paso TOTP usado (anti-replay). */
    public void registerSuccess(long totpStep, Instant now) {
        failedAttempts = 0;
        lockedUntil = null;
        lastTotpStep = totpStep;
        updatedAt = now;
    }

    /** Rotación de secretos desde la configuración (nueva contraseña o nuevo TOTP). */
    public void rotateSecrets(String newPasswordHash, String newTotpSecret, Instant now) {
        this.passwordHash = newPasswordHash;
        this.totpSecret = newTotpSecret;
        this.failedAttempts = 0;
        this.lockedUntil = null;
        this.lastTotpStep = 0;
        this.updatedAt = now;
    }
}

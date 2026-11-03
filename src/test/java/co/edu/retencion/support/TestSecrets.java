package co.edu.retencion.support;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

import co.edu.retencion.auth.domain.Totp;

/**
 * Secretos efímeros generados en cada ejecución de pruebas: nada sensible queda en el repo
 * (y el escáner de secretos del CI no encuentra falsos positivos).
 */
public final class TestSecrets {

    public static final String BREAK_GLASS_USER = "emergencia";
    public static final String BREAK_GLASS_PASSWORD = "Prueba-Emergencia-" + randomBase64(9);
    public static final byte[] TOTP_SECRET = randomBytes(20);

    private static final String JWT_SECRET = randomBase64(32);
    private static final String MASTER_KEY = randomBase64(32);
    private static final String BLIND_INDEX_KEY = randomBase64(32);
    private static final String PASSWORD_HASH =
            new Argon2PasswordEncoder(16, 32, 1, 19_456, 2).encode(BREAK_GLASS_PASSWORD);

    private TestSecrets() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwt.secret", () -> JWT_SECRET);
        registry.add("app.crypto.local.master-key", () -> MASTER_KEY);
        registry.add("app.crypto.blind-index-key", () -> BLIND_INDEX_KEY);
        registry.add("app.auth.break-glass.username", () -> BREAK_GLASS_USER);
        registry.add("app.auth.break-glass.password-hash", () -> PASSWORD_HASH);
        registry.add("app.auth.break-glass.totp-secret", () -> Totp.encodeBase32(TOTP_SECRET));
    }

    public static byte[] jwtSecret() {
        return Base64.getDecoder().decode(JWT_SECRET);
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String randomBase64(int n) {
        return Base64.getEncoder().encodeToString(randomBytes(n));
    }
}

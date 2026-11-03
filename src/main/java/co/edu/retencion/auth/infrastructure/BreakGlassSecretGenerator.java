package co.edu.retencion.auth.infrastructure;

import java.io.Console;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import co.edu.retencion.auth.domain.Totp;

/**
 * Herramienta de línea de comandos para preparar una cuenta break-glass. Pide la contraseña sin
 * mostrarla e imprime el hash argon2id, un secreto TOTP nuevo y la URI {@code otpauth://} para
 * registrarlo en una app autenticadora. No arranca Spring.
 *
 * <pre>
 * java -cp target/retencion-backend.jar \
 *   -Dloader.main=co.edu.retencion.auth.infrastructure.BreakGlassSecretGenerator \
 *   org.springframework.boot.loader.launch.PropertiesLauncher emergencia
 * </pre>
 */
public final class BreakGlassSecretGenerator {

    private BreakGlassSecretGenerator() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("Ejecuta en una terminal interactiva para leer la contraseña de forma segura.");
            System.exit(1);
        }
        String username = args.length > 0 ? args[0] : "emergencia";
        char[] password = console.readPassword("Contraseña (mínimo 16 caracteres): ");
        if (password == null || password.length < 16) {
            System.err.println("La contraseña debe tener al menos 16 caracteres.");
            System.exit(1);
        }
        String hash = AuthInfrastructureConfig.argon2().encode(new String(password));
        Arrays.fill(password, '\0');

        byte[] secret = new byte[20];
        new SecureRandom().nextBytes(secret);
        String base32 = Totp.encodeBase32(secret);
        String label = URLEncoder.encode("Retencion:" + username, StandardCharsets.UTF_8);

        System.out.println("BREAK_GLASS_USERNAME=" + username);
        System.out.println("BREAK_GLASS_PASSWORD_HASH=" + hash);
        System.out.println("BREAK_GLASS_TOTP_SECRET=" + base32);
        System.out.println("URI para la app autenticadora: otpauth://totp/" + label
                + "?secret=" + base32 + "&issuer=Retencion&digits=6&period=30");
    }
}

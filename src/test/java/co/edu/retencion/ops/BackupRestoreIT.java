package co.edu.retencion.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import co.edu.retencion.support.IntegrationTest;
import co.edu.retencion.support.TestDatabase;

/**
 * Prueba de restauración del backup: corre los scripts reales de {@code ops/backup/} contra la base de pruebas
 * (pg_dump + cifrado gpg AES-256 + suma SHA-256), restaura en una base nueva y verifica que el contenido, la
 * cadena de auditoría y sus protecciones llegaron intactos. Se omite si faltan bash, pg_dump o gpg.
 */
class BackupRestoreIT extends IntegrationTest {

    private static final String[] TABLES = {"flyway_schema_history", "users", "students", "student_identities", "enrollments",
            "app_settings", "audit_log"};

    @Test
    void anEncryptedBackupRestoresIntact() throws Exception {
        assumeTrue(available("bash", "--version") && available("pg_dump", "--version") && available("gpg", "--version"),
                "Faltan bash, pg_dump o gpg en esta máquina");
        TestDatabase db = TestDatabase.get();
        URI jdbc = URI.create(db.jdbcUrl().substring("jdbc:".length()));
        String base = "postgresql://" + db.ownerUser() + ":" + db.ownerPassword() + "@" + jdbc.getHost() + ":" + jdbc.getPort();
        String source = base + jdbc.getPath();
        String target = "restore_check_" + System.nanoTime();
        Path dir = Files.createDirectories(Path.of("target", "backup-test"));
        String dump = dir.resolve("retencion.dump.gpg").toString().replace('\\', '/');
        Map<String, String> env = Map.of("DATABASE_URL", source, "ADMIN_DATABASE_URL", source,
                "BACKUP_PASSPHRASE", "frase-de-prueba-larga-" + System.nanoTime());

        run(env, "ops/backup/backup.sh", dump);
        assertThat(Files.size(Path.of(dump))).isPositive();
        assertThat(Files.readString(Path.of(dump + ".sha256"))).contains("retencion.dump.gpg");
        assertThat(new String(Files.readAllBytes(Path.of(dump)), java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("el archivo está cifrado").doesNotContain("CREATE TABLE").doesNotContain("audit_log");

        Map<String, Long> before = counts(db.jdbcUrl());
        long sourceTriggers;
        try (Connection c = db.ownerConnection(); Statement st = c.createStatement()) {
            sourceTriggers = triggers(st);
        }
        String lastHash = lastAuditHash(db.jdbcUrl());
        run(env, "ops/backup/restore.sh", dump, target);
        String restoredUrl = db.jdbcUrl().replace(jdbc.getPath(), "/" + target);
        try {
            Map<String, Long> after = counts(restoredUrl);
            assertThat(after).as("mismas filas en cada tabla").isEqualTo(before);
            assertThat(before.get("flyway_schema_history")).isPositive();
            assertThat(before.get("app_settings")).isPositive();
            assertThat(lastAuditHash(restoredUrl)).as("la cadena de auditoría llega completa").isEqualTo(lastHash);
            try (Connection c = DriverManager.getConnection(restoredUrl, db.ownerUser(), db.ownerPassword());
                 Statement st = c.createStatement()) {
                assertThat(triggers(st)).as("los triggers append-only de la auditoría también se restauraron")
                        .isEqualTo(sourceTriggers).isPositive();
                assertThatThrownBy(() -> st.execute("TRUNCATE audit_log"))
                        .as("la auditoría sigue rechazando borrados").isInstanceOf(SQLException.class);
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_matviews WHERE matviewname LIKE 'analytics_%'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }
            }
        } finally {
            try (Connection c = db.ownerConnection(); Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + target + " WITH (FORCE)");
            }
        }
    }

    /** Triggers propios (no internos) sobre la auditoría y sus particiones. */
    private static long triggers(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                + "WHERE c.relname LIKE 'audit_log%' AND NOT t.tgisinternal")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Map<String, Long> counts(String url) throws SQLException {
        Map<String, Long> counts = new java.util.HashMap<>();
        TestDatabase db = TestDatabase.get();
        try (Connection c = DriverManager.getConnection(url, db.ownerUser(), db.ownerPassword()); Statement st = c.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
                    rs.next();
                    counts.put(table, rs.getLong(1));
                }
            }
        }
        return counts;
    }

    private static String lastAuditHash(String url) throws SQLException {
        TestDatabase db = TestDatabase.get();
        try (Connection c = DriverManager.getConnection(url, db.ownerUser(), db.ownerPassword()); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT hash FROM audit_log ORDER BY seq DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void run(Map<String, String> env, String script, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("bash", script));
        command.addAll(List.of(args));
        File log = Path.of("target", "backup-test", Path.of(script).getFileName() + ".log").toFile();
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log);
        builder.environment().putAll(env);
        Process process = builder.start();
        assertThat(process.waitFor(10, TimeUnit.MINUTES)).as("%s terminó", script).isTrue();
        assertThat(process.exitValue()).as("%s: %s", script, Files.readString(log.toPath())).isZero();
    }

    private static boolean available(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }
}

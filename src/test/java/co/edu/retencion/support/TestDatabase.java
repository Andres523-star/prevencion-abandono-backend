package co.edu.retencion.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * PostgreSQL 16 real para las pruebas de integración (las reglas de auditoría dependen de
 * triggers y permisos de PostgreSQL, así que no se usa H2).
 *
 * <ul>
 *   <li>Con Docker (CI en GitHub Actions): Testcontainers con {@code postgres:16-alpine}.</li>
 *   <li>Sin Docker (portátil de desarrollo): PostgreSQL 16 embebido (binarios de Zonky).</li>
 * </ul>
 *
 * <p>Se crea el rol {@code retencion_app} con privilegios mínimos, igual que en producción:
 * Flyway migra como superusuario y la aplicación se conecta como {@code retencion_app}.
 * Instancia única por JVM (Singleton perezoso).
 */
public final class TestDatabase {

    public static final String APP_ROLE = "retencion_app";
    public static final String APP_PASSWORD = "app_test_pw";

    private static TestDatabase instance;

    private final String jdbcUrl;
    private final String ownerUser;
    private final String ownerPassword;

    private TestDatabase(String jdbcUrl, String ownerUser, String ownerPassword) {
        this.jdbcUrl = jdbcUrl;
        this.ownerUser = ownerUser;
        this.ownerPassword = ownerPassword;
        createAppRole();
    }

    public static synchronized TestDatabase get() {
        if (instance == null) {
            instance = dockerAvailable() ? startContainer() : startEmbedded();
        }
        return instance;
    }

    public void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> ownerUser);
        registry.add("spring.flyway.password", () -> ownerPassword);
        registry.add("spring.flyway.placeholders.appDbRole", () -> APP_ROLE);
    }

    /** Conexión como dueño (superusuario) para pruebas que simulan a un atacante con privilegios. */
    public Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, ownerUser, ownerPassword);
    }

    /** Conexión con el rol de la aplicación (privilegios mínimos). */
    public Connection appConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, APP_ROLE, APP_PASSWORD);
    }

    private void createAppRole() {
        try (Connection c = ownerConnection(); Statement st = c.createStatement()) {
            st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                    + "CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "'; END IF; END $$");
        } catch (SQLException ex) {
            throw new IllegalStateException("No se pudo crear el rol de la aplicación", ex);
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ex) {
            return false;
        }
    }

    @SuppressWarnings("resource")
    private static TestDatabase startContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("retencion")
                .withUsername("owner")
                .withPassword("owner_test_pw");
        container.start();
        return new TestDatabase(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static TestDatabase startEmbedded() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // apagado de la JVM
                }
            }));
            return new TestDatabase(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres");
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo iniciar PostgreSQL embebido", ex);
        }
    }
}

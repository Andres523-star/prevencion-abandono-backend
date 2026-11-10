package co.edu.retencion.common.ops;

import java.util.Map;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Migración controlada de la base de datos, fuera del arranque de la aplicación. En Render corre como
 * {@code preDeployCommand}: si falla, el despliegue se detiene y las instancias viejas siguen sirviendo.
 * Así ninguna instancia migra al arrancar y varias instancias nunca compiten por el esquema.
 *
 * <p>Usa las mismas variables que la aplicación ({@code DB_HOST}, {@code DB_PORT}, {@code DB_NAME},
 * {@code DB_SSLMODE}, {@code DB_MIGRATION_USER}/{@code DB_MIGRATION_PASSWORD} o las del usuario de la app,
 * y {@code DB_APP_ROLE}). No levanta Spring.
 */
public final class MigrateMain {

    private static final Logger log = LoggerFactory.getLogger(MigrateMain.class);

    private MigrateMain() {
    }

    public static void main(String[] args) {
        Map<String, String> env = System.getenv();
        String url = "jdbc:postgresql://" + env.getOrDefault("DB_HOST", "localhost") + ":" + env.getOrDefault("DB_PORT", "5432")
                + "/" + env.getOrDefault("DB_NAME", "retencion") + "?sslmode=" + env.getOrDefault("DB_SSLMODE", "prefer");
        String user = env.getOrDefault("DB_MIGRATION_USER", env.getOrDefault("DB_APP_USER", "retencion_app"));
        String password = env.getOrDefault("DB_MIGRATION_PASSWORD", env.getOrDefault("DB_APP_PASSWORD", ""));
        MigrateResult result = migrate(url, user, password, env.getOrDefault("DB_APP_ROLE", "retencion_app"));
        log.info("Migraciones aplicadas: {}; versión del esquema: {}", result.migrationsExecuted, result.targetSchemaVersion);
    }

    /** Valida lo ya aplicado (checksums) y aplica lo pendiente. */
    public static MigrateResult migrate(String url, String user, String password, String appRole) {
        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .placeholders(Map.of("appDbRole", appRole))
                .validateOnMigrate(true)
                .load();
        return flyway.migrate();
    }
}

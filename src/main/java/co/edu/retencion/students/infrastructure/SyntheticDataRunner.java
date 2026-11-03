package co.edu.retencion.students.infrastructure;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Lanza el generador de datos sintéticos al arrancar si {@code app.synthetic.enabled=true}
 * (activado en el perfil dev con {@code APP_SYNTHETIC_ENABLED=true}). Nunca corre con el perfil
 * prod y no repite si ya hay estudiantes.
 */
@Component
@ConditionalOnProperty(name = "app.synthetic.enabled", havingValue = "true")
class SyntheticDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataRunner.class);

    private final SyntheticDataGenerator generator;
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final int students;

    SyntheticDataRunner(SyntheticDataGenerator generator, JdbcTemplate jdbc, Environment environment,
                        @Value("${app.synthetic.students:50000}") int students) {
        this.generator = generator;
        this.jdbc = jdbc;
        this.environment = environment;
        this.students = students;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            log.error("Datos sintéticos deshabilitados en producción");
            return;
        }
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM students", Integer.class);
        if (existing != null && existing > 0) {
            log.info("Ya hay {} estudiantes; no se generan datos sintéticos", existing);
            return;
        }
        log.info("Generando {} estudiantes sintéticos...", students);
        generator.generate(SyntheticDataGenerator.Options.defaults(students));
    }
}

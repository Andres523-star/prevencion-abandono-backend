package co.edu.retencion.common.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj único de la aplicación. Todas las fechas se guardan en UTC ({@link java.time.Instant});
 * la zona de negocio (America/Bogota) solo se usa para cálculos de calendario.
 *
 * <p>Inyectar {@link Clock} en lugar de llamar a {@code Instant.now()} permite probar
 * vencimientos y bloqueos avanzando el tiempo en los tests.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    /** Zona horaria de negocio de la universidad. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Bogota");

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

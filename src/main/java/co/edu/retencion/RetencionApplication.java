package co.edu.retencion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Punto de entrada del backend de retención estudiantil.
 *
 * <p>Monolito modular: cada subpaquete directo de {@code co.edu.retencion} es un módulo de
 * Spring Modulith (common, auth, users, audit, settings). Los límites se verifican en
 * {@code ModularityTest}.
 *
 * <p>Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RetencionApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetencionApplication.class, args);
    }
}

package co.edu.retencion.common.ops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * En producción la aplicación no migra al arrancar: solo valida que el esquema esté exactamente en la
 * versión que el código espera (migra {@link MigrateMain} antes del despliegue). Si falta una migración o
 * una ya aplicada cambió, la instancia no arranca y el despliegue falla sin tocar datos.
 */
@Configuration(proxyBeanMethods = false)
public class FlywayStartupConfig {

    @Bean
    @ConditionalOnProperty(name = "app.flyway.migrate-on-startup", havingValue = "false")
    FlywayMigrationStrategy validateOnly() {
        return flyway -> flyway.validate();
    }
}

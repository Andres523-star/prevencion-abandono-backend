package co.edu.retencion.audit.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.audit.domain.AuditEventSink;

/**
 * Compone la cadena de decoradores de auditoría:
 * métricas → depuración de PII → escritura JDBC encadenada.
 */
@Configuration(proxyBeanMethods = false)
public class AuditInfrastructureConfig {

    @Bean
    AuditEventSink auditEventSink(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        AuditEventSink base = new JdbcAuditEventSink(jdbcTemplate, objectMapper);
        return new MeteredAuditSinkDecorator(new PiiScrubbingAuditSinkDecorator(base), meterRegistry);
    }
}

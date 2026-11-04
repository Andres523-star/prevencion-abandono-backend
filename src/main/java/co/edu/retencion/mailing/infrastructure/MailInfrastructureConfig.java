package co.edu.retencion.mailing.infrastructure;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import co.edu.retencion.common.security.PublicEndpoints;
import co.edu.retencion.mailing.application.EmailSender;
import co.edu.retencion.mailing.application.MailProperties;

/**
 * <b>Patrón creacional: Factory Method.</b> Construye el proveedor de correo según
 * {@code app.mail.provider}. Falla al arrancar si el proveedor elegido no tiene su configuración
 * completa.
 *
 * <p>Asistido por IA (Claude, Anthropic).
 */
@Configuration(proxyBeanMethods = false)
public class MailInfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(MailInfrastructureConfig.class);

    @Bean
    EmailSender emailSender(MailProperties properties, RestClient.Builder http, Clock clock) {
        return switch (properties.provider().toLowerCase()) {
            case "graph" -> {
                MailProperties.Graph graph = properties.graph();
                require(graph.tenantId(), "APP_MAIL_GRAPH_TENANT_ID");
                require(graph.clientId(), "APP_MAIL_GRAPH_CLIENT_ID");
                require(graph.clientSecret(), "APP_MAIL_GRAPH_CLIENT_SECRET");
                require(graph.mailbox(), "APP_MAIL_GRAPH_MAILBOX");
                yield new GraphEmailSender(http, graph, clock);
            }
            case "resend" -> {
                require(properties.resend().apiKey(), "APP_MAIL_RESEND_API_KEY");
                require(properties.from(), "APP_MAIL_FROM");
                yield new ResendEmailSender(http, properties.resend(), properties.from());
            }
            case "memory" -> new InMemoryEmailSender();
            case "logging" -> loggingSender();
            default -> throw new IllegalStateException("app.mail.provider desconocido: " + properties.provider());
        };
    }

    /** El webhook de rebotes no lleva JWT: se autentica con firma HMAC. */
    @Bean
    PublicEndpoints mailPublicEndpoints() {
        return new PublicEndpoints(List.of("/api/v1/mail/bounces"));
    }

    /** Desarrollo: no envía; registra solo el asunto y el id (nunca la dirección ni el cuerpo). */
    private static EmailSender loggingSender() {
        return new EmailSender() {
            @Override
            public String send(EmailMessage message) {
                log.info("Correo simulado (no enviado): asunto='{}', outbox={}", message.subject(),
                        message.correlationId());
                return "log-" + message.correlationId();
            }

            @Override
            public String name() {
                return "logging";
            }
        };
    }

    private static void require(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Falta la variable " + variable + " para el proveedor de correo");
        }
    }
}

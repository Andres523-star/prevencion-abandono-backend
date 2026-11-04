package co.edu.retencion.mailing.infrastructure;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import co.edu.retencion.mailing.application.EmailSender;
import co.edu.retencion.mailing.application.MailProperties;

/**
 * <b>Patrón estructural: Adapter.</b> Adapter de Resend (API HTTP simple), alternativa a Microsoft Graph para despliegues sin
 * Microsoft 365 (p. ej. Render). El id que devuelve Resend es el que llega en sus webhooks de rebote.
 *
 * <p>Asistido por IA (Claude, Anthropic).
 */
class ResendEmailSender implements EmailSender {

    private final RestClient http;
    private final MailProperties.Resend config;
    private final String from;

    ResendEmailSender(RestClient.Builder builder, MailProperties.Resend config, String from) {
        this.http = builder.build();
        this.config = config;
        this.from = from;
    }

    @Override
    public String send(EmailMessage message) {
        Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(message.to()),
                "subject", message.subject(),
                "html", message.html(),
                "headers", Map.of("X-Retencion-Outbox-Id", message.correlationId()));
        try {
            JsonNode response = http.post()
                    .uri(config.baseUrl() + "/emails")
                    .headers(h -> h.setBearerAuth(config.apiKey()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return response != null && response.hasNonNull("id") ? response.get("id").asText() : message.correlationId();
        } catch (RestClientResponseException ex) {
            throw GraphEmailSender.failure("resend_http_" + ex.getStatusCode().value(), ex.getStatusCode());
        } catch (ResourceAccessException ex) {
            throw new EmailDeliveryException("resend_unreachable", false);
        }
    }

    @Override
    public String name() {
        return "resend";
    }
}

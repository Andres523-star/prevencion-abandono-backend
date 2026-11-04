package co.edu.retencion.mailing.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import co.edu.retencion.mailing.application.EmailSender;
import co.edu.retencion.mailing.application.MailProperties;

/**
 * <b>Patrón estructural: Adapter.</b> Adapter de Microsoft Graph {@code sendMail}: client credentials (permiso de aplicación
 * {@code Mail.Send} restringido al buzón con una application access policy) y envío desde el
 * buzón institucional. El token se cachea hasta un minuto antes de vencer.
 *
 * <p>Graph responde 202 sin id; se envía la cabecera {@code x-retencion-outbox-id} y ese mismo id
 * se usa para asociar los rebotes.
 *
 * <p>Asistido por IA (Claude, Anthropic).
 */
class GraphEmailSender implements EmailSender {

    private final RestClient http;
    private final MailProperties.Graph config;
    private final Clock clock;

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    GraphEmailSender(RestClient.Builder builder, MailProperties.Graph config, Clock clock) {
        this.http = builder.build();
        this.config = config;
        this.clock = clock;
    }

    @Override
    public String send(EmailMessage message) {
        Map<String, Object> body = Map.of(
                "message", Map.of(
                        "subject", message.subject(),
                        "body", Map.of("contentType", "HTML", "content", message.html()),
                        "toRecipients", List.of(Map.of("emailAddress", Map.of("address", message.to()))),
                        "internetMessageHeaders", List.of(
                                Map.of("name", "x-retencion-outbox-id", "value", message.correlationId()))),
                "saveToSentItems", false);
        try {
            http.post()
                    .uri(config.baseUrl() + "/v1.0/users/{mailbox}/sendMail", config.mailbox())
                    .headers(h -> h.setBearerAuth(accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return message.correlationId();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                invalidateToken();
            }
            throw failure("graph_http_" + ex.getStatusCode().value(), ex.getStatusCode());
        } catch (ResourceAccessException ex) {
            throw new EmailDeliveryException("graph_unreachable", false);
        }
    }

    @Override
    public String name() {
        return "graph";
    }

    private synchronized String accessToken() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpiresAt)) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("scope", "https://graph.microsoft.com/.default");
        form.add("grant_type", "client_credentials");
        try {
            JsonNode response = http.post()
                    .uri(config.authority() + "/{tenant}/oauth2/v2.0/token", config.tenantId())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.hasNonNull("access_token")) {
                throw new EmailDeliveryException("graph_token_invalid", false);
            }
            token = response.get("access_token").asText();
            tokenExpiresAt = now.plusSeconds(Math.max(60, response.path("expires_in").asLong(3600) - 60));
            return token;
        } catch (RestClientResponseException ex) {
            throw failure("graph_token_http_" + ex.getStatusCode().value(), ex.getStatusCode());
        } catch (ResourceAccessException ex) {
            throw new EmailDeliveryException("graph_token_unreachable", false);
        }
    }

    private synchronized void invalidateToken() {
        token = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    /** 429 y 5xx se reintentan; los demás 4xx no (configuración o destinatario inválido). */
    static EmailDeliveryException failure(String reason, HttpStatusCode status) {
        boolean retryable = status.value() == 429 || status.value() == 401 || status.is5xxServerError();
        return new EmailDeliveryException(reason, !retryable);
    }
}

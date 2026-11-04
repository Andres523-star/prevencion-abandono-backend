package co.edu.retencion.mailing.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuración del correo ({@code app.mail.*}). Las credenciales llegan solo por variables de
 * entorno. El modo de envío (tras la ventana de edición o resumen diario) y la hora del resumen
 * son parámetros de negocio y viven en settings ({@code mail.absence-mode}, {@code mail.digest-hour}).
 *
 * @param provider        {@code logging} (dev), {@code memory} (tests), {@code graph} (Microsoft Graph) o {@code resend}
 * @param dispatchEnabled si el despachador programado está activo
 * @param batchSize       filas reclamadas por ciclo
 * @param maxAttempts     intentos antes de marcar FALLIDO
 * @param backoffBase     espera del primer reintento (se duplica en cada intento)
 * @param backoffMax      espera máxima entre reintentos
 * @param lease           tiempo tras el cual una fila ENVIANDO huérfana (caída de la instancia) se reintenta
 * @param from            remitente visible (Resend; en Graph es el buzón)
 * @param portalUrl       enlace al portal autenticado del estudiante
 * @param webhookSecret   secreto HMAC del webhook de rebotes (vacío = webhook deshabilitado)
 * @param graph           Microsoft Graph (client credentials, permiso restringido al buzón)
 * @param resend          Resend (alternativa simple para despliegues sin Microsoft 365)
 */
@ConfigurationProperties("app.mail")
public record MailProperties(@DefaultValue("logging") String provider,
                             @DefaultValue("true") boolean dispatchEnabled,
                             @DefaultValue("50") int batchSize,
                             @DefaultValue("8") int maxAttempts,
                             @DefaultValue("1m") Duration backoffBase,
                             @DefaultValue("6h") Duration backoffMax,
                             @DefaultValue("5m") Duration lease,
                             @DefaultValue("") String from,
                             @DefaultValue("http://localhost:3000") String portalUrl,
                             @DefaultValue("") String webhookSecret,
                             @DefaultValue Graph graph,
                             @DefaultValue Resend resend) {

    public record Graph(@DefaultValue("") String tenantId, @DefaultValue("") String clientId,
                        @DefaultValue("") String clientSecret, @DefaultValue("") String mailbox,
                        @DefaultValue("https://login.microsoftonline.com") String authority,
                        @DefaultValue("https://graph.microsoft.com") String baseUrl) {
        @Override
        public String toString() {
            return "Graph[tenantId=" + tenantId + ", mailbox=" + mailbox + ", clientSecret=***]";
        }
    }

    public record Resend(@DefaultValue("") String apiKey, @DefaultValue("https://api.resend.com") String baseUrl) {
        @Override
        public String toString() {
            return "Resend[apiKey=***]";
        }
    }

    @Override
    public String toString() {
        return "MailProperties[provider=" + provider + ", dispatchEnabled=" + dispatchEnabled + "]";
    }
}

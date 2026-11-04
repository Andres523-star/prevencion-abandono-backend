package co.edu.retencion.ai.infrastructure;

import java.io.InterruptedIOException;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import co.edu.retencion.ai.AiFailure;
import co.edu.retencion.ai.application.LlmGateway;

/**
 * <b>Patrón Adapter:</b> adapta el SDK oficial de Anthropic para Java al puerto {@link LlmGateway}.
 * Traduce la jerarquía de excepciones del SDK (de la más específica a la más general) a un motivo
 * normalizado ({@link AiFailure}); así los decoradores y el fallback no dependen del SDK.
 *
 * <p>Los reintentos ante 429/5xx/red los hace el SDK ({@code ai.max-retries}, respetando
 * {@code retry-after}); aquí llega el error solo cuando se agotaron.
 */
public class AnthropicLlmGateway implements LlmGateway {

    private final AnthropicClient client;

    public AnthropicLlmGateway(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public Message send(String purpose, MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (RateLimitException ex) {
            throw new LlmException(AiFailure.RATE_LIMITED, "El proveedor de IA respondió 429", ex);
        } catch (InternalServerException ex) {
            throw new LlmException(AiFailure.UNAVAILABLE, "El proveedor de IA respondió " + ex.statusCode(), ex);
        } catch (UnauthorizedException | PermissionDeniedException ex) {
            throw new LlmException(AiFailure.NOT_CONFIGURED, "Credenciales de IA rechazadas", ex);
        } catch (BadRequestException ex) {
            throw new LlmException(AiFailure.ERROR, "Petición rechazada por el proveedor de IA", ex);
        } catch (AnthropicServiceException ex) {
            throw new LlmException(AiFailure.ERROR, "Error del proveedor de IA (" + ex.statusCode() + ")", ex);
        } catch (AnthropicIoException ex) {
            AiFailure reason = hasCause(ex, InterruptedIOException.class) ? AiFailure.TIMEOUT : AiFailure.UNAVAILABLE;
            throw new LlmException(reason, "Sin respuesta del proveedor de IA", ex);
        } catch (AnthropicInvalidDataException ex) {
            throw new LlmException(AiFailure.ERROR, "Respuesta ilegible del proveedor de IA", ex);
        }
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}

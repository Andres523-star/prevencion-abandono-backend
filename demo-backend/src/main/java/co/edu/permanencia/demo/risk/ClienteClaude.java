package co.edu.permanencia.demo.risk;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Adaptador (patrón Adapter) entre la demo y la API de Claude, con el SDK oficial de Anthropic
 * para Java, el mismo que usa el backend principal.
 *
 * <p>Modelo por defecto: {@code claude-sonnet-5-5} (propiedad {@code demo.ai.model}). Sin
 * {@code ANTHROPIC_API_KEY} no se crea el cliente y la demo usa su motor local. La clave nunca
 * se registra en los logs; ante cualquier error se devuelve vacío y el llamador conserva el texto
 * del motor local.</p>
 */
@Component
public class ClienteClaude {

    private static final Logger log = LoggerFactory.getLogger(ClienteClaude.class);
    private static final String SISTEMA = """
            Eres un asesor de permanencia universitaria. Redactas en español, con tono cálido y
            concreto, para el profesor que acompaña a un estudiante. Recibes solo datos académicos
            seudonimizados: nunca pidas ni inventes nombres, documentos ni datos de contacto, y no
            uses etiquetas alarmantes. Responde con un único párrafo de máximo 120 palabras que
            incluya tres pasos concretos.""";

    private final String modelo;
    private final AnthropicClient client;

    public ClienteClaude(@Value("${demo.ai.api-key:}") String apiKey,
                         @Value("${demo.ai.model:claude-sonnet-5-5}") String modelo,
                         @Value("${demo.ai.base-url:https://api.anthropic.com}") String baseUrl,
                         @Value("${demo.ai.timeout-seconds:30}") long timeoutSegundos) {
        this.modelo = modelo;
        this.client = apiKey == null || apiKey.isBlank() ? null : AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(timeoutSegundos))
                .maxRetries(2)
                .build();
    }

    /** True si hay clave configurada. */
    public boolean disponible() {
        return client != null;
    }

    public String modelo() {
        return modelo;
    }

    /**
     * Pide a Claude el párrafo para el caso. El {@code caso} ya debe venir seudonimizado (lo arma
     * {@link AnalisisService#datosParaIa}).
     */
    public Optional<String> redactar(String caso) {
        if (client == null) {
            return Optional.empty();
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(modelo)
                .maxTokens(1024L)
                .system(SISTEMA)
                .addUserMessage(caso)
                // Un párrafo corto no necesita razonamiento largo: esfuerzo bajo, más rápido y barato.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of("low")).build())
                .build();
        try {
            return texto(client.messages().create(params));
        } catch (AnthropicException | IllegalStateException ex) {
            // Solo el tipo de error: el mensaje del proveedor podría incluir el contenido enviado.
            log.warn("No se pudo usar Claude ({}); se mantiene el motor local", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Junta todos los bloques de texto de la respuesta. Antes se leía solo el primer bloque y, si
     * no era de texto, se perdía la respuesta en silencio.
     */
    static Optional<String> texto(Message message) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock bloque : message.content()) {
            bloque.text().ifPresent(t -> sb.append(t.text()));
        }
        String texto = sb.toString().strip();
        return texto.isEmpty() ? Optional.empty() : Optional.of(texto);
    }
}

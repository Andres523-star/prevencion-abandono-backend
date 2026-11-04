package co.edu.retencion.ai.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import co.edu.retencion.ai.AiFailure;
import co.edu.retencion.ai.application.AiProperties;
import co.edu.retencion.ai.application.AiUsageService;
import co.edu.retencion.ai.application.LlmGateway;
import co.edu.retencion.ai.domain.PayloadWhitelist;
import co.edu.retencion.ai.domain.PiiRedactor;

/**
 * Arma la cadena hacia Claude: {@link BudgetedLlmGateway} → {@link ResilientLlmGateway} →
 * {@link AnthropicLlmGateway}. Sin {@code ANTHROPIC_API_KEY} no se crea cliente y todo análisis cae
 * al motor de reglas.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {

    static final String RESILIENCE_INSTANCE = "anthropic";

    @Bean
    LlmGateway llmGateway(AiProperties properties, AiUsageService usage, CircuitBreakerRegistry circuitBreakers,
                          RateLimiterRegistry rateLimiters, BulkheadRegistry bulkheads) {
        if (!properties.configured()) {
            return (purpose, params) -> {
                throw new LlmGateway.LlmException(AiFailure.NOT_CONFIGURED, "Sin ANTHROPIC_API_KEY");
            };
        }
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .maxRetries(properties.maxRetries())
                .timeout(properties.timeout())
                .build();
        LlmGateway adapter = new AnthropicLlmGateway(client);
        LlmGateway resilient = new ResilientLlmGateway(adapter, circuitBreakers.circuitBreaker(RESILIENCE_INSTANCE),
                rateLimiters.rateLimiter(RESILIENCE_INSTANCE), bulkheads.bulkhead(RESILIENCE_INSTANCE));
        return new BudgetedLlmGateway(resilient, usage);
    }

    /** Redactor con el diccionario {@code ai/nombres-comunes.txt} ({@code *} = palabra ambigua). */
    @Bean
    PiiRedactor piiRedactor() {
        Set<String> names = new HashSet<>();
        Set<String> ambiguous = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("ai/nombres-comunes.txt").getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.strip();
                if (word.isEmpty() || word.startsWith("#")) {
                    continue;
                }
                if (word.endsWith("*")) {
                    word = word.substring(0, word.length() - 1);
                    ambiguous.add(word);
                }
                names.add(word);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo cargar el diccionario de nombres", ex);
        }
        return new PiiRedactor(names, ambiguous);
    }

    @Bean
    PayloadWhitelist payloadWhitelist(PiiRedactor redactor) {
        return new PayloadWhitelist(PayloadWhitelist.RISK_PAYLOAD, redactor);
    }
}

package co.edu.retencion.auth.infrastructure.supabase;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import co.edu.retencion.auth.domain.AuthProblems;
import co.edu.retencion.auth.infrastructure.AuthProperties;
import co.edu.retencion.common.error.DomainException;

/**
 * Cliente de la API de Supabase Auth ({@code GET /auth/v1/user}). Se consulta una vez por login
 * para obtener datos autoritativos que el JWT no trae (correo confirmado) y para comprobar que la
 * sesión de Supabase sigue viva (no fue cerrada ni revocada). Protegido con circuit breaker.
 */
@Component
public class SupabaseAuthClient {

    /** Usuario según Supabase (solo lo que se usa; el resto de campos se ignora). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupabaseUser(
            String id,
            String email,
            @JsonProperty("email_confirmed_at") Instant emailConfirmedAt,
            @JsonProperty("user_metadata") Map<String, Object> userMetadata) {

        @Override
        public String toString() {
            return "SupabaseUser[id=" + id + "]";
        }
    }

    private final AuthProperties properties;
    private final RestClient http;

    public SupabaseAuthClient(AuthProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.http = builder.requestFactory(requestFactory).build();
    }

    @CircuitBreaker(name = "supabase")
    public SupabaseUser fetchUser(String accessToken) {
        AuthProperties.Supabase supabase = properties.supabase();
        try {
            SupabaseUser user = http.get()
                    .uri(supabase.userEndpoint())
                    .header("apikey", supabase.anonKey())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(SupabaseUser.class);
            if (user == null || user.id() == null) {
                throw new DomainException(AuthProblems.AUTH_PROVIDER_ERROR, "Respuesta inválida de Supabase");
            }
            return user;
        } catch (HttpClientErrorException ex) {
            // 401/403: la sesión de Supabase se cerró o el token fue revocado.
            throw new DomainException(AuthProblems.INVALID_PROVIDER_TOKEN, "La sesión de Supabase ya no es válida");
        } catch (RestClientException ex) {
            throw new DomainException(AuthProblems.AUTH_PROVIDER_ERROR, "No se pudo contactar a Supabase Auth");
        }
    }
}

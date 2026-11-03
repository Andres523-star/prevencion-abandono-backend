package co.edu.retencion.common.crypto;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * <b>Patrón estructural: Adapter.</b> Adapta la API HTTP del motor Transit de HashiCorp Vault a
 * nuestra interfaz {@link KmsProvider}. La clave maestra vive solo en Vault:
 * <ul>
 *   <li>{@code POST /v1/transit/datakey/plaintext/{key}} genera una DEK (en claro + envuelta).</li>
 *   <li>{@code POST /v1/transit/decrypt/{key}} desenvuelve una DEK guardada.</li>
 * </ul>
 * Un adaptador equivalente puede escribirse para OCI Vault o AWS KMS sin tocar el resto del código.
 */
public class VaultTransitKmsAdapter implements KmsProvider {

    private final RestClient http;
    private final String keyName;

    public VaultTransitKmsAdapter(String vaultUri, String token, String keyName) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder()
                .baseUrl(vaultUri)
                .requestFactory(requestFactory)
                .defaultHeader("X-Vault-Token", token)
                .build();
        this.keyName = keyName;
    }

    @Override
    public String activeKeyId() {
        return keyName;
    }

    @Override
    public DataKey generateDataKey() {
        JsonNode data = post("/v1/transit/datakey/plaintext/{key}", Map.of("bits", 256), keyName);
        byte[] plaintext = Base64.getDecoder().decode(data.path("plaintext").asText());
        byte[] wrapped = data.path("ciphertext").asText().getBytes(StandardCharsets.UTF_8);
        return new DataKey(keyName, plaintext, wrapped);
    }

    @Override
    public byte[] decryptDataKey(String keyId, byte[] wrappedKey) {
        String ciphertext = new String(wrappedKey, StandardCharsets.UTF_8);
        JsonNode data = post("/v1/transit/decrypt/{key}", Map.of("ciphertext", ciphertext), keyId);
        return Base64.getDecoder().decode(data.path("plaintext").asText());
    }

    private JsonNode post(String path, Map<String, Object> body, String key) {
        try {
            JsonNode response = http.post().uri(path, key).body(body).retrieve().body(JsonNode.class);
            if (response == null || response.path("data").isMissingNode()) {
                throw new CryptoException("Respuesta inválida de Vault");
            }
            return response.path("data");
        } catch (RestClientException ex) {
            throw new CryptoException("Vault no disponible", ex);
        }
    }
}

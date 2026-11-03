package co.edu.retencion.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Supabase Auth simulado sobre WireMock: publica el JWKS del proyecto (claves ES256, como los
 * proyectos actuales de Supabase), firma access tokens y responde {@code GET /auth/v1/user}.
 * Permite fabricar tokens con emisor, audiencia, rol o firma incorrectos para probar cada validación.
 */
public final class FakeSupabase {

    public static final String ANON_KEY = "anon-key-de-prueba";

    private static FakeSupabase instance;

    private final WireMockServer server;
    private final ECKey signingKey;
    private final ECKey rogueKey;

    private FakeSupabase() {
        try {
            this.signingKey = new ECKeyGenerator(Curve.P_256).keyID("supabase-test-key").generate();
            this.rogueKey = new ECKeyGenerator(Curve.P_256).keyID("supabase-test-key").generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        this.server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        server.stubFor(WireMock.get(urlEqualTo("/auth/v1/.well-known/jwks.json"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(new JWKSet(signingKey.toPublicJWK()).toString())));
    }

    public static synchronized FakeSupabase get() {
        if (instance == null) {
            instance = new FakeSupabase();
        }
        return instance;
    }

    public String issuer() {
        return server.baseUrl() + "/auth/v1";
    }

    public void register(DynamicPropertyRegistry registry) {
        registry.add("app.auth.supabase.url", server::baseUrl);
        registry.add("app.auth.supabase.anon-key", () -> ANON_KEY);
    }

    /** Datos del access token a emitir; los "with" rompen una validación concreta. */
    public record AccessToken(String subject, String email, String aal, String issuer, String audience,
                              String role, boolean anonymous, Instant issuedAt, boolean rogueSignature) {

        public static AccessToken of(String subject, String email, String aal, Instant now) {
            return new AccessToken(subject, email, aal, null, "authenticated", "authenticated", false, now, false);
        }

        public AccessToken withIssuer(String value) {
            return new AccessToken(subject, email, aal, value, audience, role, anonymous, issuedAt, rogueSignature);
        }

        public AccessToken withAudience(String value) {
            return new AccessToken(subject, email, aal, issuer, value, role, anonymous, issuedAt, rogueSignature);
        }

        public AccessToken anonymousUser() {
            return new AccessToken(subject, email, aal, issuer, audience, "anon", true, issuedAt, rogueSignature);
        }

        public AccessToken issuedAt(Instant value) {
            return new AccessToken(subject, email, aal, issuer, audience, role, anonymous, value, rogueSignature);
        }

        public AccessToken withRogueSignature() {
            return new AccessToken(subject, email, aal, issuer, audience, role, anonymous, issuedAt, true);
        }
    }

    public String sign(AccessToken t) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(t.issuer() != null ? t.issuer() : issuer())
                    .audience(t.audience())
                    .subject(t.subject())
                    .issueTime(Date.from(t.issuedAt()))
                    .expirationTime(Date.from(t.issuedAt().plusSeconds(3600)))
                    .claim("email", t.email())
                    .claim("role", t.role())
                    .claim("aal", t.aal())
                    .claim("is_anonymous", t.anonymous())
                    .claim("session_id", UUID.randomUUID().toString())
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .keyID("supabase-test-key").type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new ECDSASigner(t.rogueSignature() ? rogueKey : signingKey));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Programa {@code GET /auth/v1/user} para ese token. */
    public void willReturnUser(String token, String subject, String email, boolean emailConfirmed) {
        String confirmedAt = emailConfirmed ? "\"2026-01-01T00:00:00.000000Z\"" : "null";
        server.stubFor(WireMock.get(urlEqualTo("/auth/v1/user"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .withHeader("apikey", equalTo(ANON_KEY))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"id":"%s","aud":"authenticated","role":"authenticated","email":"%s",
                         "email_confirmed_at":%s,"app_metadata":{"provider":"email"},
                         "user_metadata":{"full_name":"Persona de Prueba"}}
                        """.formatted(subject, email, confirmedAt))));
    }

    /** Programa {@code GET /auth/v1/user} con un error HTTP (sesión revocada, caída...). */
    public void willFailUser(String token, int status) {
        server.stubFor(WireMock.get(urlEqualTo("/auth/v1/user"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(aResponse().withStatus(status)));
    }
}

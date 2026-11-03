package co.edu.retencion.common.security.jwt;

import java.time.Clock;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Codificador y decodificador del JWT de acceso de la plataforma (HS256 con secreto de entorno).
 *
 * <p>El token de Supabase solo se usa una vez, al iniciar sesión; después la API se protege con
 * este token propio, corto y con los roles asignados en la plataforma.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformJwtConfig {

    @Bean
    SecretKey platformJwtKey(PlatformJwtProperties properties) {
        return new SecretKeySpec(properties.secretBytes(), "HmacSHA256");
    }

    @Bean
    JwtEncoder platformJwtEncoder(SecretKey platformJwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(platformJwtKey));
    }

    @Bean
    JwtDecoder platformJwtDecoder(SecretKey platformJwtKey, PlatformJwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(platformJwtKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD, aud -> aud != null && aud.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(properties.issuer()),
                audience,
                PlatformJwtConfig::requireSubjectAndSession));
        return decoder;
    }

    private static OAuth2TokenValidatorResult requireSubjectAndSession(Jwt jwt) {
        if (jwt.getSubject() == null || jwt.getClaimAsString(PlatformClaims.SESSION_ID) == null) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Faltan claims obligatorios", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}

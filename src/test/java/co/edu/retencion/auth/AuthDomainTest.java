package co.edu.retencion.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import co.edu.retencion.auth.domain.AuthMethod;
import co.edu.retencion.auth.domain.LocalCredential;
import co.edu.retencion.auth.domain.LockoutPolicy;
import co.edu.retencion.auth.domain.MfaPolicy;
import co.edu.retencion.auth.domain.RefreshToken;
import co.edu.retencion.auth.domain.Totp;
import co.edu.retencion.auth.infrastructure.AuthProperties;
import co.edu.retencion.auth.infrastructure.SecureTokens;
import co.edu.retencion.auth.infrastructure.supabase.SupabaseAuthClient.SupabaseUser;
import co.edu.retencion.auth.infrastructure.supabase.SupabaseIdentityAdapter;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.Role;

/** Reglas de dominio de autenticación, sin Spring. */
class AuthDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-03T15:00:00Z");

    @Test
    void totpMatchesRfc6238TestVector() {
        // RFC 6238, apéndice B: secreto ASCII "12345678901234567890", T=59 s → 94287082 (8 dígitos).
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertThat(Totp.generate(secret, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.decodeBase32(Totp.encodeBase32(secret))).isEqualTo(secret);
    }

    @Test
    void totpAcceptsAdjacentStepAndRejectsReplay() {
        byte[] secret = Totp.decodeBase32("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP");
        long step = Totp.stepAt(NOW);
        assertThat(Totp.verify(secret, Totp.generate(secret, step - 1), NOW, 0)).hasValue(step - 1);
        assertThat(Totp.verify(secret, Totp.generate(secret, step), NOW, step)).isEmpty();
        assertThat(Totp.verify(secret, Totp.generate(secret, step - 5), NOW, 0)).isEmpty();
        assertThat(Totp.verify(secret, "12ab56", NOW, 0)).isEmpty();
        assertThatThrownBy(() -> Totp.decodeBase32("no-base32!")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lockoutDoublesUpToMaximum() {
        LockoutPolicy policy = new LockoutPolicy(3, Duration.ofSeconds(60), Duration.ofMinutes(5));
        assertThat(policy.lockUntil(2, NOW)).isEmpty();
        assertThat(policy.lockUntil(3, NOW)).contains(NOW.plusSeconds(60));
        assertThat(policy.lockUntil(4, NOW)).contains(NOW.plusSeconds(120));
        assertThat(policy.lockUntil(5, NOW)).contains(NOW.plusSeconds(240));
        assertThat(policy.lockUntil(50, NOW)).contains(NOW.plusSeconds(300));
        assertThatThrownBy(() -> new LockoutPolicy(0, Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localCredentialTracksFailuresAndSuccess() {
        LocalCredential c = LocalCredential.create(UUID.randomUUID(), "emergencia", "$argon2id$x", "SECRET", NOW);
        LockoutPolicy policy = new LockoutPolicy(2, Duration.ofSeconds(30), Duration.ofMinutes(1));
        c.registerFailure(NOW, policy);
        assertThat(c.isLocked(NOW)).isFalse();
        c.registerFailure(NOW, policy);
        assertThat(c.isLocked(NOW)).isTrue();
        assertThat(c.isLocked(NOW.plusSeconds(31))).isFalse();
        c.registerSuccess(99, NOW);
        assertThat(c.getFailedAttempts()).isZero();
        assertThat(c.getLastTotpStep()).isEqualTo(99);
        c.rotateSecrets("$argon2id$y", "OTRO", NOW);
        assertThat(c.getLastTotpStep()).isZero();
    }

    @Test
    void mfaPolicyOnlyAffectsPrivilegedRoles() {
        MfaPolicy.enforce(Set.of(Role.DOCENTE, Role.ESTUDIANTE), false);
        MfaPolicy.enforce(Set.of(Role.ADMIN), true);
        for (Role privileged : Set.of(Role.ADMIN, Role.CONSEJERO, Role.PSICOLOGO, Role.COORDINADOR)) {
            assertThatThrownBy(() -> MfaPolicy.enforce(Set.of(privileged), false))
                    .isInstanceOfSatisfying(DomainException.class, ex -> {
                        assertThat(ex.problemCode()).isEqualTo("mfa_required");
                        assertThat(ex.getMessage()).contains(privileged.name());
                    });
        }
    }

    @Test
    void refreshTokenNeverOutlivesItsFamily() {
        Instant familyEnd = NOW.plus(Duration.ofHours(1));
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), null, UUID.randomUUID(), "h", AuthMethod.SUPABASE,
                false, NOW, NOW.plus(Duration.ofHours(8)), familyEnd);
        assertThat(token.getExpiresAt()).isEqualTo(familyEnd);
        assertThat(token.isConsumed()).isFalse();
        token.markUsed(NOW);
        assertThat(token.isConsumed()).isTrue();
        assertThat(token.isExpired(familyEnd)).isTrue();
    }

    @Test
    void secureTokensAreRandomAndHashable() {
        assertThat(SecureTokens.randomToken()).hasSize(43).isNotEqualTo(SecureTokens.randomToken());
        // SHA-256("abc"), vector conocido.
        assertThat(SecureTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void emptyDomainListAcceptsAnyEmail() {
        AuthProperties any = properties(List.of());
        assertThat(any.isAllowedEmail("alguien@gmail.com")).isTrue();
        assertThat(any.isAllowedEmail("sin-arroba")).isFalse();
        AuthProperties restricted = properties(List.of("universidad.edu.co"));
        assertThat(restricted.isAllowedEmail("ana@Universidad.edu.co")).isTrue();
        assertThat(restricted.isAllowedEmail("ana@gmail.com")).isFalse();
    }

    @Test
    void supabaseAdapterNormalizesIdentity() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256")
                .issuer("https://demo.supabase.co/auth/v1").subject("sub-1")
                .claim("email", "respaldo@gmail.com").claim("aal", "aal2").build();
        var external = new SupabaseIdentityAdapter().adapt(jwt,
                new SupabaseUser("sub-1", " Ana@Gmail.com ", NOW, Map.of("full_name", "Ana Pérez")));
        assertThat(external.identity().email()).isEqualTo("ana@gmail.com");
        assertThat(external.identity().displayName()).isEqualTo("Ana Pérez");
        assertThat(external.identity().subject()).isEqualTo("sub-1");
        assertThat(external.emailVerified()).isTrue();
        assertThat(external.aal()).isEqualTo("aal2");

        var unverified = new SupabaseIdentityAdapter().adapt(jwt, new SupabaseUser("sub-1", null, null, null));
        assertThat(unverified.emailVerified()).isFalse();
        assertThat(unverified.identity().email()).isEqualTo("respaldo@gmail.com");
        assertThat(unverified.identity().displayName()).isEqualTo("respaldo@gmail.com");

        assertThatThrownBy(() -> new SupabaseIdentityAdapter().adapt(jwt, new SupabaseUser("otro", "a@b.co", NOW, null)))
                .isInstanceOf(DomainException.class);
    }

    private static AuthProperties properties(List<String> domains) {
        return new AuthProperties(domains, Duration.ofHours(8), Duration.ofHours(24),
                new AuthProperties.Supabase("https://demo.supabase.co", "anon", null, "authenticated", true, "aal2"),
                new AuthProperties.BreakGlass(false, null, null, null));
    }
}

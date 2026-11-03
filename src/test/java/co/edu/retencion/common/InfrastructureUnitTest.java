package co.edu.retencion.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import co.edu.retencion.common.security.CorsConfig;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.security.jwt.PlatformJwtProperties;
import co.edu.retencion.common.web.ClientIpResolver;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.common.web.HttpProperties;
import co.edu.retencion.settings.SettingChanged;
import co.edu.retencion.settings.SettingsProvider;
import co.edu.retencion.settings.infrastructure.CachingSettingsProxy;
import co.edu.retencion.users.application.ProvisioningPolicy;

/** Piezas transversales sin contexto Spring. */
class InfrastructureUnitTest {

    @Test
    void clientIpIgnoresSpoofableLeftmostForwardedEntries() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 181.50.1.2");

        assertThat(new ClientIpResolver(new HttpProperties(0, null)).resolve(request)).isEqualTo("10.0.0.5");
        assertThat(new ClientIpResolver(new HttpProperties(1, null)).resolve(request)).isEqualTo("181.50.1.2");
        assertThat(new ClientIpResolver(new HttpProperties(2, null)).resolve(request)).isEqualTo("6.6.6.6");

        request.addHeader("True-Client-IP", "190.1.1.1");
        assertThat(new ClientIpResolver(new HttpProperties(1, "True-Client-IP")).resolve(request)).isEqualTo("190.1.1.1");
    }

    @Test
    void cursorPageDetectsNextPage() {
        List<java.util.UUID> ids = List.of(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        CursorPage<java.util.UUID> page = CursorPage.of(ids, 2, id -> id);
        assertThat(page.items()).hasSize(2);
        assertThat(page.nextCursor()).isEqualTo(ids.get(1).toString());
        assertThat(CursorPage.of(ids, 5, id -> id).nextCursor()).isNull();
        assertThat(CursorPage.clampSize(null)).isEqualTo(50);
        assertThat(CursorPage.clampSize(10_000)).isEqualTo(CursorPage.MAX_SIZE);
    }

    @Test
    void corsRejectsWildcards() {
        assertThatThrownBy(() -> new CorsConfig.CorsProperties(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CorsConfig.CorsProperties(List.of("https://app.universidad.edu.co")).allowedOrigins()).hasSize(1);
    }

    @Test
    void jwtPropertiesEnforceShortTtlAndStrongSecret() {
        assertThatThrownBy(() -> new PlatformJwtProperties("i", "a", "c2hvcnQ=", Duration.ofHours(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlatformJwtProperties("i", "a", "c2hvcnQ=", Duration.ofMinutes(10), Duration.ZERO).secretBytes())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cachingSettingsProxyHitsDatabaseOnceUntilInvalidated() {
        AtomicInteger calls = new AtomicInteger();
        SettingsProvider real = key -> {
            calls.incrementAndGet();
            return Optional.<JsonNode>of(IntNode.valueOf(calls.get()));
        };
        CachingSettingsProxy proxy = new CachingSettingsProxy(real, Duration.ofMinutes(5));

        assertThat(proxy.getInt("k", 0)).isEqualTo(1);
        assertThat(proxy.getInt("k", 0)).isEqualTo(1);
        assertThat(calls).hasValue(1);
        proxy.on(new SettingChanged("k"));
        assertThat(proxy.getInt("k", 0)).isEqualTo(2);
        assertThat(proxy.getBoolean("k", true)).as("tipo distinto → valor por defecto").isTrue();
        assertThat(proxy.getSeconds("k", Duration.ZERO)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void provisioningPolicyNeverGrantsPrivilegedRolesByDomain() {
        ProvisioningPolicy policy = new ProvisioningPolicy(List.of("Rectoria@Universidad.edu.co"),
                List.of("estudiantes.universidad.edu.co=ESTUDIANTE"));
        assertThat(policy.initialRoles("rectoria@universidad.edu.co")).containsExactly(Role.ADMIN);
        assertThat(policy.initialRoles("ana@estudiantes.universidad.edu.co")).containsExactly(Role.ESTUDIANTE);
        assertThat(policy.initialRoles("docente@universidad.edu.co")).isEmpty();
        assertThatThrownBy(() -> new ProvisioningPolicy(List.of(), List.of("universidad.edu.co=ADMIN")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Set.of(Role.values())).hasSize(6);
    }
}

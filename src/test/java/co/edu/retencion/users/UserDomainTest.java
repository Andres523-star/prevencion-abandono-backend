package co.edu.retencion.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.users.domain.User;

/** Invariantes del agregado User. */
class UserDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-03T15:00:00Z");

    @Test
    void externalUserStartsActiveWithoutRoles() {
        User user = User.provisionExternal("https://demo.supabase.co/auth/v1", "sub", "a@u.edu.co", "idx", "Ana", NOW);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getAuthType()).isEqualTo(AuthType.SUPABASE);
        assertThat(user.roles()).isEmpty();
        assertThat(user.getId().version()).isEqualTo(7);
    }

    @Test
    void replacingRolesKeepsGrantMetadataForUnchangedRoles() {
        User user = User.createLocal("Emergencia", NOW);
        UUID admin = UUID.randomUUID();
        user.replaceRoles(Set.of(Role.DOCENTE, Role.CONSEJERO), admin, NOW);
        user.replaceRoles(Set.of(Role.DOCENTE, Role.ADMIN), admin, NOW.plusSeconds(60));

        assertThat(user.roles()).containsExactlyInAnyOrder(Role.DOCENTE, Role.ADMIN);
        assertThat(user.getRoleGrants()).filteredOn(g -> g.getRole() == Role.DOCENTE)
                .singleElement().satisfies(g -> assertThat(g.getGrantedAt()).isEqualTo(NOW));
    }

    @Test
    void linkingToADifferentIdentityIsRejected() {
        User user = User.provisionExternal("https://demo.supabase.co/auth/v1", "sub-1", "a@u.edu.co", "idx", "Ana", NOW);
        user.linkExternal("https://demo.supabase.co/auth/v1", "sub-1", NOW);
        assertThatThrownBy(() -> user.linkExternal("https://demo.supabase.co/auth/v1", "sub-2", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void profileRefreshOnlyTouchesUpdatedAtWhenSomethingChanged() {
        User user = User.provisionExternal("https://demo.supabase.co/auth/v1", "sub", "a@u.edu.co", "idx", "Ana", NOW);
        user.refreshProfile("a@u.edu.co", "idx", "Ana", NOW.plusSeconds(10));
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        user.refreshProfile("ana.p@u.edu.co", "idx-2", "Ana P.", NOW.plusSeconds(20));
        assertThat(user.getEmail()).isEqualTo("ana.p@u.edu.co");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    void statusProgramsAndLoginAreTracked() {
        User user = User.createLocal("Emergencia", NOW);
        UUID program = UUID.randomUUID();
        user.replacePrograms(Set.of(program), NOW);
        user.changeStatus(UserStatus.DISABLED, NOW);
        user.recordLogin(NOW.plusSeconds(5));
        assertThat(user.getProgramIds()).containsExactly(program);
        assertThat(user.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(user.getLastLoginAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(user.getEmail()).isNull();
    }
}

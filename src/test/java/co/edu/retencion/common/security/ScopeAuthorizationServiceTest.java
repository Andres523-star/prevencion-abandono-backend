package co.edu.retencion.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.security.authorization.AccessAction;
import co.edu.retencion.common.security.authorization.ResourceScope;
import co.edu.retencion.common.security.authorization.ResourceScopeResolver;
import co.edu.retencion.common.security.authorization.ScopeAuthorizationService;
import co.edu.retencion.common.security.authorization.TeachingAssignmentProvider;

/** Reglas ABAC por rol: programa, grupo y titular del dato. */
class ScopeAuthorizationServiceTest {

    private static final UUID PROGRAM_A = UUID.randomUUID();
    private static final UUID PROGRAM_B = UUID.randomUUID();
    private static final UUID GROUP_1 = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STUDENT_RECORD = UUID.randomUUID();
    private static final UUID TEACHER = UUID.randomUUID();

    private final ResourceScopeResolver resolver = new ResourceScopeResolver() {
        private final Map<UUID, ResourceScope> data = Map.of(STUDENT_RECORD,
                new ResourceScope("STUDENT", STUDENT_RECORD, Set.of(PROGRAM_A), Set.of(GROUP_1), OWNER));

        @Override
        public String resourceType() {
            return "STUDENT";
        }

        @Override
        public Optional<ResourceScope> resolve(UUID id) {
            return Optional.ofNullable(data.get(id));
        }
    };

    private final TeachingAssignmentProvider teaching = userId -> userId.equals(TEACHER) ? Set.of(GROUP_1) : Set.of();
    private final ScopeAuthorizationService abac = new ScopeAuthorizationService(List.of(resolver), Optional.of(teaching));

    private static AuthenticatedUser user(UUID id, Set<Role> roles, Set<UUID> programs) {
        return new AuthenticatedUser(id, roles, programs, true, UUID.randomUUID());
    }

    private boolean can(AuthenticatedUser user) {
        return abac.evaluate(user, "STUDENT", STUDENT_RECORD, AccessAction.READ);
    }

    @Test
    void adminSeesEverything() {
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.ADMIN), Set.of()))).isTrue();
    }

    @Test
    void coordinatorAndCounselorAreLimitedToTheirProgram() {
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.COORDINADOR), Set.of(PROGRAM_A)))).isTrue();
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.COORDINADOR), Set.of(PROGRAM_B)))).isFalse();
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.CONSEJERO), Set.of(PROGRAM_A)))).isTrue();
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.CONSEJERO), Set.of()))).isFalse();
    }

    @Test
    void psychologistIsInstitutionWideUnlessRestricted() {
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.PSICOLOGO), Set.of()))).isTrue();
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.PSICOLOGO), Set.of(PROGRAM_B)))).isFalse();
    }

    @Test
    void teacherOnlySeesStudentsOfOwnGroups() {
        assertThat(can(user(TEACHER, Set.of(Role.DOCENTE), Set.of()))).isTrue();
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.DOCENTE), Set.of()))).isFalse();
    }

    @Test
    void studentOnlySeesOwnData() {
        assertThat(can(user(OWNER, Set.of(Role.ESTUDIANTE), Set.of()))).isTrue();
        assertThat(can(user(UUID.randomUUID(), Set.of(Role.ESTUDIANTE), Set.of()))).isFalse();
    }

    @Test
    void ownerCanReadButNotWriteWithoutRole() {
        AuthenticatedUser owner = user(OWNER, Set.of(), Set.of());
        assertThat(abac.evaluate(owner, "STUDENT", STUDENT_RECORD, AccessAction.READ)).isTrue();
        assertThat(abac.evaluate(owner, "STUDENT", STUDENT_RECORD, AccessAction.WRITE)).isFalse();
    }

    @Test
    void failsClosedForUnknownTypesOrRecords() {
        AuthenticatedUser admin = user(UUID.randomUUID(), Set.of(Role.ADMIN), Set.of());
        assertThat(abac.evaluate(admin, "DESCONOCIDO", STUDENT_RECORD, AccessAction.READ)).isFalse();
        assertThat(abac.evaluate(admin, "STUDENT", UUID.randomUUID(), AccessAction.READ)).isFalse();
        assertThat(abac.evaluate(admin, "STUDENT", null, AccessAction.READ)).isFalse();
        assertThat(abac.canAccess("STUDENT", STUDENT_RECORD, "READ")).as("sin sesión").isFalse();
    }
}

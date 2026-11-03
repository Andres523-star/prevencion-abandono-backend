package co.edu.retencion.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.audit.application.AuditChainVerifier;
import co.edu.retencion.audit.domain.ChainVerification;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.support.IntegrationTest;
import co.edu.retencion.support.TestDatabase;

/**
 * Hash encadenado: la verificación pasa con la bitácora intacta y detecta la fila alterada
 * aunque un superusuario se salte el trigger. También comprueba la depuración de PII.
 */
class AuditChainIT extends IntegrationTest {

    @Autowired
    private AuditTrail auditTrail;
    @Autowired
    private AuditChainVerifier verifier;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void intactChainVerifiesThroughAdminEndpoint() throws Exception {
        auditTrail.record("TEST_CHAIN", "Test", "a", AuditOutcome.SUCCESS, Map.of("k", "v"));
        Tokens admin = loginAs(Set.of(Role.ADMIN), Set.of());
        mvc.perform(get("/api/v1/audit/verify").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void tamperingIsDetected() throws Exception {
        auditTrail.record("TEST_TAMPER", "Test", "b", AuditOutcome.SUCCESS, Map.of());
        long seq = jdbc.queryForObject("SELECT max(seq) FROM audit_log WHERE action = 'TEST_TAMPER'", Long.class);
        String original = jdbc.queryForObject("SELECT entity_id FROM audit_log WHERE seq = ?", String.class, seq);

        tamper(seq, "manipulado");
        try {
            ChainVerification result = verifier.verify();
            assertThat(result.valid()).isFalse();
            assertThat(result.firstBrokenSeq()).isEqualTo(seq);
        } finally {
            tamper(seq, original);
        }
        assertThat(verifier.verify().valid()).isTrue();
    }

    @Test
    void personalDataInDetailsIsScrubbed() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("email", "persona@universidad.edu.co");
        details.put("nota", "llamar al 300 123 4567 o escribir a otra@correo.com");
        details.put("familyId", "01960000-1234-7abc-8def-0123456789ab");
        auditTrail.record("TEST_PII", "Test", "c", AuditOutcome.SUCCESS, details);

        String stored = jdbc.queryForObject(
                "SELECT details::text FROM audit_log WHERE action = 'TEST_PII' ORDER BY seq DESC LIMIT 1", String.class);
        assertThat(stored)
                .doesNotContain("persona@universidad.edu.co")
                .doesNotContain("otra@correo.com")
                .doesNotContain("123 4567")
                .contains("01960000-1234-7abc-8def-0123456789ab");
    }

    /** Simula a un superusuario malicioso: desactiva triggers en su sesión y altera una fila. */
    private static void tamper(long seq, String entityId) throws Exception {
        try (Connection c = TestDatabase.get().ownerConnection()) {
            try (Statement st = c.createStatement()) {
                st.execute("SET session_replication_role = replica");
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE audit_log SET entity_id = ? WHERE seq = ?")) {
                ps.setString(1, entityId);
                ps.setLong(2, seq);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }
    }
}

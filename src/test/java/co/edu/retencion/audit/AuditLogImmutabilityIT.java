package co.edu.retencion.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.support.IntegrationTest;
import co.edu.retencion.support.TestDatabase;

/**
 * Criterio de aceptación: {@code audit_log} rechaza UPDATE, DELETE y TRUNCATE.
 * <ul>
 *   <li>Rol de la aplicación: sin privilegios (permission denied, SQLSTATE 42501).</li>
 *   <li>Dueño/superusuario: lo bloquea el trigger append-only.</li>
 * </ul>
 */
class AuditLogImmutabilityIT extends IntegrationTest {

    private static final String PERMISSION_DENIED = "42501";

    @Autowired
    private AuditTrail auditTrail;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void ensureRows() {
        auditTrail.record("TEST_IMMUTABILITY", "Test", "1", AuditOutcome.SUCCESS, Map.of());
    }

    @Test
    void appRoleCannotUpdateDeleteOrTruncate() throws Exception {
        try (Connection c = TestDatabase.get().appConnection(); Statement st = c.createStatement()) {
            assertSqlState(() -> st.executeUpdate("UPDATE audit_log SET reason = 'x'"), PERMISSION_DENIED);
            assertSqlState(() -> st.executeUpdate("DELETE FROM audit_log"), PERMISSION_DENIED);
            assertSqlState(() -> st.execute("TRUNCATE audit_log"), PERMISSION_DENIED);
            String partition = currentPartition();
            assertSqlState(() -> st.executeUpdate("UPDATE " + partition + " SET reason = 'x'"), PERMISSION_DENIED);
            assertSqlState(() -> st.executeUpdate("DELETE FROM " + partition), PERMISSION_DENIED);
        }
    }

    @Test
    void appRoleCanStillInsertAndRead() throws Exception {
        try (Connection c = TestDatabase.get().appConnection(); Statement st = c.createStatement()) {
            assertThat(st.executeQuery("SELECT count(*) FROM audit_log").next()).isTrue();
        }
    }

    @Test
    void triggerBlocksModificationsEvenForOwner() throws Exception {
        try (Connection c = TestDatabase.get().ownerConnection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE audit_log SET reason = 'alterado'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM audit_log"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.execute("TRUNCATE audit_log"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            String partition = currentPartition();
            assertThatThrownBy(() -> st.execute("TRUNCATE " + partition))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        }
    }

    @Test
    void tableIsPartitionedByMonth() {
        Integer partitions = jdbc.queryForObject(
                "SELECT count(*) FROM pg_inherits WHERE inhparent = 'audit_log'::regclass", Integer.class);
        assertThat(partitions).isGreaterThanOrEqualTo(2); // mes actual (+ siguientes) y DEFAULT
        assertThat(currentPartition()).startsWith("audit_log_");
    }

    private String currentPartition() {
        return jdbc.queryForObject("SELECT format('audit_log_%s', to_char(now(), 'YYYY_MM'))", String.class);
    }

    @FunctionalInterface
    private interface SqlAction {
        Object run() throws SQLException;
    }

    private static void assertSqlState(SqlAction action, String expectedState) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo(expectedState));
    }
}

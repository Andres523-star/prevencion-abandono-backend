package co.edu.retencion.ops;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import co.edu.retencion.common.ops.MigrateMain;
import co.edu.retencion.support.IntegrationTest;
import co.edu.retencion.support.TestDatabase;

/** La migración controlada valida lo aplicado y no hace nada si el esquema ya está al día. */
class MigrateMainIT extends IntegrationTest {

    @Test
    void migratingAnUpToDateSchemaIsANoOp() {
        TestDatabase db = TestDatabase.get();
        MigrateResult result = MigrateMain.migrate(db.jdbcUrl(), db.ownerUser(), db.ownerPassword(), TestDatabase.APP_ROLE);
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isZero();
        assertThat(result.targetSchemaVersion == null ? result.initialSchemaVersion : result.targetSchemaVersion).isNotBlank();
    }
}

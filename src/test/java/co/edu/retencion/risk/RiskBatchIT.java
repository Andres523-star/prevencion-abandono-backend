package co.edu.retencion.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import co.edu.retencion.risk.application.RiskBatchLauncher;
import co.edu.retencion.support.AttendanceWorld;
import co.edu.retencion.support.FakeClaude;
import co.edu.retencion.support.RiskIntegrationTest;

/**
 * Lote nocturno (Spring Batch particionado por programa): solo analiza a los estudiantes activos
 * cuyo payload cambió o que están en vigilancia; cada análisis es una fila nueva.
 */
class RiskBatchIT extends RiskIntegrationTest {

    /** Odontología: ninguna otra prueba cambia su política de asistencia (IIN sí, en AttendanceRulesIT). */
    static final java.util.UUID ODO = java.util.UUID.fromString("0195f000-0000-7000-8000-000000000006");

    @Autowired
    RiskBatchLauncher batch;

    @Test
    void onlyChangedOrWatchedStudentsAreReanalyzed() throws Exception {
        CLAUDE.willReturnExplanation("claude-haiku-5-5", FakeClaude.validExplanation("MEDIO", "TUT-ACADEMICA"));
        AttendanceWorld w = world(ODO);
        markAbsence(w, 0);

        RiskBatchLauncher.RunSummary first = batch.run(Set.of("ODO"));
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.read()).isGreaterThanOrEqualTo(1);
        assertThat(assessmentsOf(w.studentId())).isEqualTo(1);
        String trigger = jdbc.queryForObject("SELECT trigger_type FROM risk_assessments WHERE student_id = ?",
                String.class, w.studentId());
        assertThat(trigger).isEqualTo("BATCH");

        // Nada cambió: el hash del payload es el mismo y no está en vigilancia.
        RiskBatchLauncher.RunSummary unchanged = batch.run(Set.of("ODO"));
        assertThat(unchanged.unchanged()).isGreaterThanOrEqualTo(1);
        assertThat(assessmentsOf(w.studentId())).isEqualTo(1);

        // Segunda falta: cambia el payload y el umbral de aviso lo deja en vigilancia.
        markAbsence(w, 1);
        batch.run(Set.of("ODO"));
        assertThat(assessmentsOf(w.studentId())).isEqualTo(2);
        // Sin cambios, pero en vigilancia: se vuelve a analizar.
        batch.run(Set.of("ODO"));
        assertThat(assessmentsOf(w.studentId())).isEqualTo(3);

        // Un estudiante de otro programa no entra al lote limitado a ODO.
        AttendanceWorld other = world(DER);
        markAbsence(other, 0);
        batch.run(Set.of("ODO"));
        assertThat(assessmentsOf(other.studentId())).isZero();
    }
}

package co.edu.retencion.support;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.students.infrastructure.SyntheticDataGenerator;
import co.edu.retencion.students.infrastructure.SyntheticDataGenerator.Options;

/**
 * Datos sintéticos para analítica: un periodo completo del generador y, por SQL, lo que normalmente
 * dejan meses de operación: nivel de riesgo, retiros, contadores de faltas y casos de retención. La
 * distribución es determinista (según el orden del id) para poder comparar con las tablas base.
 */
public final class AnalyticsDataset {

    private AnalyticsDataset() {
    }

    /** Periodo generado. */
    public record Dataset(UUID termId, String termCode, int students) {
    }

    public static Dataset create(SyntheticDataGenerator generator, JdbcTemplate jdbc, int students) {
        String tag = "A" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        SyntheticDataGenerator.Summary summary = generator.generate(new Options(students, 2, 5, tag));
        UUID term = jdbc.queryForObject("SELECT id FROM terms WHERE code = ?", UUID.class, summary.termCode());
        jdbc.update("""
                INSERT INTO risk_student_state (student_id, last_level, last_score, last_assessed_at, program_id, semester,
                                                updated_at)
                SELECT id, CASE WHEN n % 10 < 2 THEN 'ALTO' WHEN n % 10 < 5 THEN 'MEDIO' ELSE 'BAJO' END,
                       CASE WHEN n % 10 < 2 THEN 75 WHEN n % 10 < 5 THEN 45 ELSE 10 END, now(), program_id, current_semester, now()
                FROM (SELECT s.id, s.program_id, s.current_semester, row_number() OVER (ORDER BY s.id) AS n
                      FROM students s WHERE s.id IN (SELECT student_id FROM enrollments WHERE term_id = ?)) x
                ON CONFLICT (student_id) DO NOTHING
                """, term);
        jdbc.update("""
                UPDATE students SET status = 'RETIRADO' WHERE id IN
                  (SELECT id FROM (SELECT s.id, row_number() OVER (ORDER BY s.id) AS n FROM students s
                                   WHERE s.id IN (SELECT student_id FROM enrollments WHERE term_id = ?)) x WHERE n % 10 = 7)
                """, term);
        jdbc.update("""
                INSERT INTO absence_counters (enrollment_id, student_id, group_id, term_id, unjustified, counted_absences,
                                              max_absences, level, updated_at)
                SELECT id, student_id, group_id, term_id, n % 5, n % 5, 4,
                       CASE n % 5 WHEN 2 THEN 'AVISO' WHEN 3 THEN 'CRITICO' WHEN 4 THEN 'PERDIDA' ELSE 'NINGUNO' END, now()
                FROM (SELECT e.*, row_number() OVER (ORDER BY e.id) AS n FROM enrollments e WHERE e.term_id = ?) x
                ON CONFLICT (enrollment_id) DO NOTHING
                """, term);
        jdbc.update("""
                INSERT INTO retention_cases (id, student_id, program_id, term_id, stage, origin, outcome, exit_cause_code,
                                             opened_at, contacted_at, closed_at, updated_at)
                SELECT gen_random_uuid(), s.id, s.program_id, ?, 'CERRADO', 'CONSEJERO', 'RETIRADO', 'OTRA', now(), now(), now(), now()
                FROM students s WHERE s.status = 'RETIRADO' AND s.id IN (SELECT student_id FROM enrollments WHERE term_id = ?)
                """, term, term);
        return new Dataset(term, summary.termCode(), summary.students());
    }
}

package co.edu.retencion.students;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import co.edu.retencion.students.infrastructure.SyntheticDataGenerator;
import co.edu.retencion.students.infrastructure.SyntheticDataGenerator.Options;
import co.edu.retencion.support.IntegrationTest;

/**
 * Generador de datos sintéticos a escala reducida (en dev genera 50.000 estudiantes): crea la
 * estructura completa y la identidad queda cifrada.
 */
class SyntheticDataIT extends IntegrationTest {

    @Autowired
    private SyntheticDataGenerator generator;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void generatesConsistentEncryptedDataset() {
        String tag = "S" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        SyntheticDataGenerator.Summary summary = generator.generate(new Options(600, 2, 2, tag));

        assertThat(summary.students()).isEqualTo(600);
        assertThat(summary.subjects()).isEqualTo(6 * 2 * 2);
        assertThat(summary.groups()).isGreaterThanOrEqualTo(summary.subjects());
        assertThat(summary.enrollments()).isGreaterThan(1_000);
        assertThat(summary.sessions()).isPositive();

        Integer plain = jdbc.queryForObject("""
                SELECT count(*) FROM student_identities i JOIN students s ON s.id = i.student_id
                JOIN enrollments e ON e.student_id = s.id JOIN course_groups g ON g.id = e.group_id
                JOIN terms t ON t.id = g.term_id
                WHERE t.code = ? AND (i.full_name_ciphertext NOT LIKE 'v1.%' OR i.email_ciphertext LIKE '%@%')
                """, Integer.class, summary.termCode());
        assertThat(plain).isZero();
        Integer overCapacity = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT g.id FROM course_groups g JOIN terms t ON t.id = g.term_id
                JOIN enrollments e ON e.group_id = g.id WHERE t.code = ? GROUP BY g.id, g.capacity
                HAVING count(*) > g.capacity) x
                """, Integer.class, summary.termCode());
        assertThat(overCapacity).isZero();
    }
}

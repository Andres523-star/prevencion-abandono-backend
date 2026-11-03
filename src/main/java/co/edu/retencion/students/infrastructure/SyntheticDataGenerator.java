package co.edu.retencion.students.infrastructure;

import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.academic.AcademicCatalog;
import co.edu.retencion.academic.AcademicCatalog.GroupCommand;
import co.edu.retencion.academic.AcademicCatalog.ScheduleSlot;
import co.edu.retencion.academic.AcademicCatalog.SubjectCommand;
import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupType;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.academic.TermInfo;
import co.edu.retencion.common.crypto.BlindIndexer;
import co.edu.retencion.common.crypto.EnvelopeEncryptor;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.students.TransportMode;
import co.edu.retencion.students.domain.AnonCodeGenerator;
import co.edu.retencion.users.UserDirectory;

/**
 * Datos sintéticos para desarrollo y pruebas de volumen (por defecto 50.000 estudiantes) con
 * Datafaker: periodo activo, festivos de Colombia, docentes, plan de estudios de los 6 programas,
 * grupos con horario, estudiantes con identidad cifrada y perfil socioeconómico, inscripciones y
 * sesiones de clase.
 *
 * <p>La estructura académica se crea por la API pública de academic; los estudiantes se insertan
 * por lotes JDBC (cifrando cada campo igual que la entidad) porque decenas de miles de filas por
 * JPA tardarían demasiado. Todos los datos son ficticios: correos en el dominio reservado
 * {@code .test}.
 */
@Component
public class SyntheticDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(SyntheticDataGenerator.class);
    private static final String[] PROGRAMS = {"ENF", "MED", "DER", "ISW", "IIN", "ODO"};
    private static final int STUDENTS_PER_GROUP = 40;
    private static final int BATCH = 1_000;
    private static final DayOfWeek[][] DAY_PAIRS = {
            {DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY}, {DayOfWeek.TUESDAY, DayOfWeek.THURSDAY},
            {DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY}, {DayOfWeek.MONDAY, DayOfWeek.THURSDAY}};

    private final AcademicCatalog catalog;
    private final AcademicDirectory directory;
    private final UserDirectory users;
    private final JdbcTemplate jdbc;
    private final EnvelopeEncryptor encryptor;
    private final BlindIndexer blindIndexer;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SyntheticDataGenerator(AcademicCatalog catalog, AcademicDirectory directory, UserDirectory users,
                                  JdbcTemplate jdbc, EnvelopeEncryptor encryptor, BlindIndexer blindIndexer,
                                  PlatformTransactionManager txManager, Clock clock) {
        this.catalog = catalog;
        this.directory = directory;
        this.users = users;
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.blindIndexer = blindIndexer;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    /**
     * @param students            cantidad de estudiantes
     * @param semesters           semestres del plan de estudios por programa
     * @param subjectsPerSemester materias por semestre
     * @param tag                 prefijo para códigos y correos (permite varias generaciones)
     */
    public record Options(int students, int semesters, int subjectsPerSemester, String tag) {

        public static Options defaults(int students) {
            return new Options(students, 10, 4, "SINT");
        }
    }

    /** Resumen de lo generado. */
    public record Summary(String termCode, int teachers, int subjects, int groups, int students, int enrollments,
                          int sessions) {
    }

    /** Materia generada con sus grupos y un contador para repartir estudiantes. */
    private static final class SubjectSlot {
        private final List<UUID> groupIds = new ArrayList<>();
        private int assigned;

        UUID nextGroup() {
            UUID group = groupIds.get(Math.min(assigned / STUDENTS_PER_GROUP, groupIds.size() - 1));
            assigned++;
            return group;
        }
    }

    public Summary generate(Options options) {
        long started = System.nanoTime();
        Faker faker = new Faker(Locale.forLanguageTag("es-CO"));
        String tag = options.tag().toUpperCase(Locale.ROOT);
        TermInfo term = catalog.ensureTerm(tag + "-2026-2", "Periodo sintético " + tag, LocalDate.of(2026, 8, 3),
                LocalDate.of(2026, 11, 27), "ACTIVE");
        catalog.loadColombianHolidays(2026);

        int teacherCount = Math.max(5, options.students() / 250);
        List<UUID> teachers = new ArrayList<>();
        for (int i = 1; i <= teacherCount; i++) {
            teachers.add(users.upsertStaff("docente" + i + "." + tag.toLowerCase(Locale.ROOT) + "@sintetico.test",
                    faker.name().fullName(), Role.DOCENTE, null).userId());
        }

        int perProgram = Math.max(1, options.students() / PROGRAMS.length);
        int perSemester = Math.max(1, perProgram / options.semesters());
        int groupsPerSubject = Math.max(1, (int) Math.ceil(perSemester / (double) STUDENTS_PER_GROUP));
        // [programa][semestre][materia]
        SubjectSlot[][][] plan = new SubjectSlot[PROGRAMS.length][options.semesters()][options.subjectsPerSemester()];
        UUID[] programIds = new UUID[PROGRAMS.length];
        int subjects = 0;
        int groups = 0;
        int teacherCursor = 0;
        for (int p = 0; p < PROGRAMS.length; p++) {
            ProgramInfo program = directory.findProgramByCode(PROGRAMS[p]).orElseThrow();
            programIds[p] = program.id();
            for (int s = 0; s < options.semesters(); s++) {
                for (int k = 0; k < options.subjectsPerSemester(); k++) {
                    String code = tag + "-" + PROGRAMS[p] + "-" + (s + 1) + "0" + (k + 1);
                    List<String> prerequisites = s == 0 ? List.of() : List.of(tag + "-" + PROGRAMS[p] + "-" + s + "0" + (k + 1));
                    UUID subjectId = catalog.upsertSubject(new SubjectCommand(program.id(), code,
                            "Materia " + (s + 1) + "." + (k + 1) + " de " + program.name(), 3, s + 1, prerequisites)).id();
                    subjects++;
                    SubjectSlot slot = new SubjectSlot();
                    for (int g = 0; g < groupsPerSubject; g++) {
                        DayOfWeek[] days = DAY_PAIRS[(k + g) % DAY_PAIRS.length];
                        LocalTime start = LocalTime.of(7 + 2 * (g % 6), 0);
                        List<ScheduleSlot> schedule = List.of(
                                new ScheduleSlot(days[0], start, start.plusHours(2), "A-" + (100 + g)),
                                new ScheduleSlot(days[1], start, start.plusHours(2), "A-" + (100 + g)));
                        GroupType type = program.clinical() && s >= options.semesters() - 2 && k == 0
                                ? GroupType.PRACTICA_CLINICA : GroupType.REGULAR;
                        UUID teacher = teachers.get(teacherCursor++ % teachers.size());
                        slot.groupIds.add(catalog.upsertGroup(new GroupCommand(subjectId, term.id(), "G" + (g + 1),
                                teacher, STUDENTS_PER_GROUP + 5, type, schedule)).id());
                        groups++;
                    }
                    plan[p][s][k] = slot;
                }
            }
        }

        int created = 0;
        int enrollments = 0;
        String documentPrefix = String.valueOf(100_000 + random.nextInt(900_000));
        List<Object[]> studentRows = new ArrayList<>();
        List<Object[]> identityRows = new ArrayList<>();
        List<Object[]> profileRows = new ArrayList<>();
        List<Object[]> enrollmentRows = new ArrayList<>();
        Instant now = clock.instant();
        for (int i = 0; i < options.students(); i++) {
            int p = i % PROGRAMS.length;
            int s = (i / PROGRAMS.length) % options.semesters();
            UUID studentId = UuidV7.newId();
            String status = random.nextInt(100) < 94 ? "ACTIVO" : (random.nextBoolean() ? "APLAZADO" : "RETIRADO");
            LocalDate admission = LocalDate.of(2026 - s / 2, s % 2 == 0 ? 8 : 2, 1);
            studentRows.add(new Object[] {studentId, AnonCodeGenerator.next(), programIds[p], s + 1, status,
                    Date.valueOf(admission), Timestamp.from(now), Timestamp.from(now)});

            String fullName = faker.name().firstName() + " " + faker.name().lastName() + " " + faker.name().lastName();
            String document = documentPrefix + String.format("%06d", i);
            String email = asciiSlug(fullName) + "." + i + "@sintetico.test";
            String phone = "3" + (100_000_000 + random.nextInt(900_000_000));
            identityRows.add(new Object[] {studentId, encryptor.encrypt(fullName), "CC", encryptor.encrypt(document),
                    blindIndexer.index(BlindIndexer.Type.DOCUMENT, document), encryptor.encrypt(email),
                    blindIndexer.index(BlindIndexer.Type.EMAIL, email), encryptor.encrypt(phone), Timestamp.from(now)});
            profileRows.add(new Object[] {studentId, 1 + random.nextInt(6), random.nextInt(100) < 35,
                    random.nextInt(100) < 80 ? 0 : 1 + random.nextInt(3),
                    TransportMode.values()[random.nextInt(TransportMode.values().length)].name(), Timestamp.from(now)});
            if ("ACTIVO".equals(status)) {
                for (int k = 0; k < options.subjectsPerSemester(); k++) {
                    UUID group = plan[p][s][k].nextGroup();
                    enrollmentRows.add(new Object[] {UuidV7.newId(), studentId, group, term.id(), Timestamp.from(now)});
                }
            }
            if (studentRows.size() >= BATCH || i == options.students() - 1) {
                created += studentRows.size();
                enrollments += enrollmentRows.size();
                flush(studentRows, identityRows, profileRows, enrollmentRows);
            }
        }
        int sessions = catalog.generateSessionsForTerm(term.id());
        Summary summary = new Summary(term.code(), teacherCount, subjects, groups, created, enrollments, sessions);
        log.info("Datos sintéticos generados en {} s: {}", (System.nanoTime() - started) / 1_000_000_000, summary);
        return summary;
    }

    private void flush(List<Object[]> students, List<Object[]> identities, List<Object[]> profiles,
                       List<Object[]> enrollments) {
        tx.executeWithoutResult(status -> {
            jdbc.batchUpdate("""
                    INSERT INTO students (id, anon_code, program_id, current_semester, status, admission_date,
                                          created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)""", students);
            jdbc.batchUpdate("""
                    INSERT INTO student_identities (student_id, full_name_ciphertext, document_type, document_ciphertext,
                                                    document_blind_index, email_ciphertext, email_blind_index,
                                                    phone_ciphertext, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""", identities);
            jdbc.batchUpdate("""
                    INSERT INTO socioeconomic_profiles (student_id, stratum, works, dependents, transport_mode,
                                                        updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, 0)""", profiles);
            jdbc.batchUpdate("""
                    INSERT INTO enrollments (id, student_id, group_id, term_id, status, enrolled_at, version)
                    VALUES (?, ?, ?, ?, 'ACTIVA', ?, 0)""", enrollments);
        });
        students.clear();
        identities.clear();
        profiles.clear();
        enrollments.clear();
    }

    private static String asciiSlug(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", ".").replaceAll("(^\\.|\\.$)", "");
    }
}

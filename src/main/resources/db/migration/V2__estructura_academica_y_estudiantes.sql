-- =====================================================================================
-- V2: Estructura académica, estudiantes e importación masiva (Fase 2)
-- Módulos: academic (programas, periodos, materias, grupos, horarios, sesiones, políticas de
-- asistencia), students (estudiante, identidad cifrada, perfil socioeconómico, inscripciones,
-- consentimiento) e imports (trabajos de importación CSV/Excel).
-- Los permisos del rol de la app salen del ALTER DEFAULT PRIVILEGES de V1.
-- Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
-- =====================================================================================

-- ---------------------------------------------------------------- Programas (6 sembrados)
CREATE TABLE programs (
    id         uuid         PRIMARY KEY,
    code       varchar(16)  NOT NULL UNIQUE,
    name       varchar(120) NOT NULL,
    -- Admite grupos de práctica / rotación clínica.
    clinical   boolean      NOT NULL DEFAULT false,
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    version    bigint       NOT NULL DEFAULT 0
);

-- IDs fijos (formato UUID v7) para poder referenciarlos en documentación y pruebas.
INSERT INTO programs (id, code, name, clinical) VALUES
    ('0195f000-0000-7000-8000-000000000001', 'ENF', 'Enfermería', true),
    ('0195f000-0000-7000-8000-000000000002', 'MED', 'Medicina', true),
    ('0195f000-0000-7000-8000-000000000003', 'DER', 'Derecho', false),
    ('0195f000-0000-7000-8000-000000000004', 'ISW', 'Ingeniería de Software', false),
    ('0195f000-0000-7000-8000-000000000005', 'IIN', 'Ingeniería Industrial', false),
    ('0195f000-0000-7000-8000-000000000006', 'ODO', 'Odontología', true);

-- ---------------------------------------------------------------- Calendario
CREATE TABLE terms (
    id         uuid         PRIMARY KEY,
    code       varchar(20)  NOT NULL UNIQUE,
    name       varchar(120) NOT NULL,
    start_date date         NOT NULL,
    end_date   date         NOT NULL,
    status     varchar(16)  NOT NULL CHECK (status IN ('PLANNED', 'ACTIVE', 'CLOSED')),
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_terms_dates CHECK (end_date > start_date)
);

CREATE TABLE holidays (
    holiday_date date         PRIMARY KEY,
    name         varchar(120) NOT NULL
);

-- Suspensión de clases: global (program_id NULL) o de un programa.
CREATE TABLE class_suspensions (
    id         uuid         PRIMARY KEY,
    start_date date         NOT NULL,
    end_date   date         NOT NULL,
    program_id uuid         REFERENCES programs (id),
    reason     varchar(300) NOT NULL,
    created_by uuid,
    created_at timestamptz  NOT NULL,
    CONSTRAINT ck_class_suspensions_dates CHECK (end_date >= start_date)
);
CREATE INDEX ix_class_suspensions_dates ON class_suspensions (start_date, end_date);

-- ---------------------------------------------------------------- Materias y grupos
CREATE TABLE subjects (
    id                 uuid         PRIMARY KEY,
    program_id         uuid         NOT NULL REFERENCES programs (id),
    code               varchar(30)  NOT NULL,
    name               varchar(200) NOT NULL,
    credits            integer      NOT NULL CHECK (credits BETWEEN 0 AND 30),
    suggested_semester integer      NOT NULL CHECK (suggested_semester BETWEEN 1 AND 14),
    active             boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_subjects_program_code UNIQUE (program_id, code)
);

CREATE TABLE subject_prerequisites (
    subject_id      uuid NOT NULL REFERENCES subjects (id),
    prerequisite_id uuid NOT NULL REFERENCES subjects (id),
    PRIMARY KEY (subject_id, prerequisite_id),
    CONSTRAINT ck_subject_prerequisites_self CHECK (subject_id <> prerequisite_id)
);

-- "group" es palabra reservada en SQL: la tabla se llama course_groups.
CREATE TABLE course_groups (
    id              uuid        PRIMARY KEY,
    subject_id      uuid        NOT NULL REFERENCES subjects (id),
    term_id         uuid        NOT NULL REFERENCES terms (id),
    code            varchar(20) NOT NULL,
    teacher_user_id uuid        REFERENCES users (id),
    capacity        integer     NOT NULL CHECK (capacity BETWEEN 1 AND 1000),
    group_type      varchar(24) NOT NULL CHECK (group_type IN ('REGULAR', 'PRACTICA_CLINICA')),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_course_groups_natural UNIQUE (subject_id, term_id, code)
);
CREATE INDEX ix_course_groups_teacher ON course_groups (teacher_user_id, term_id);
CREATE INDEX ix_course_groups_term ON course_groups (term_id);

CREATE TABLE class_schedules (
    id          uuid        PRIMARY KEY,
    group_id    uuid        NOT NULL REFERENCES course_groups (id),
    day_of_week integer     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time  time        NOT NULL,
    end_time    time        NOT NULL,
    room        varchar(60),
    CONSTRAINT ck_class_schedules_times CHECK (end_time > start_time)
);
CREATE INDEX ix_class_schedules_group ON class_schedules (group_id);

-- Sesión concreta: la falta se cuenta por sesión, no por horas.
CREATE TABLE class_sessions (
    id                  uuid         PRIMARY KEY,
    group_id            uuid         NOT NULL REFERENCES course_groups (id),
    schedule_id         uuid         REFERENCES class_schedules (id) ON DELETE SET NULL,
    session_date        date         NOT NULL,
    start_time          time         NOT NULL,
    end_time            time         NOT NULL,
    room                varchar(60),
    status              varchar(16)  NOT NULL CHECK (status IN ('PROGRAMADA', 'DICTADA', 'CANCELADA')),
    cancellation_reason varchar(300),
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL DEFAULT 0,
    -- grupo + fecha: índice para consultas por grupo y rango de fechas, e idempotencia de la generación.
    CONSTRAINT uq_class_sessions_slot UNIQUE (group_id, session_date, start_time)
);
CREATE INDEX ix_class_sessions_date ON class_sessions (session_date, status);

-- ---------------------------------------------------------------- Políticas de asistencia
-- Resolución: SUBJECT > PROGRAM_CLINICAL (grupos de práctica) > PROGRAM > valores por defecto.
CREATE TABLE attendance_policies (
    id                        uuid        PRIMARY KEY,
    scope                     varchar(20) NOT NULL CHECK (scope IN ('PROGRAM', 'PROGRAM_CLINICAL', 'SUBJECT')),
    program_id                uuid        REFERENCES programs (id),
    subject_id                uuid        REFERENCES subjects (id),
    max_absences              integer     NOT NULL,
    warning_threshold         integer     NOT NULL,
    critical_threshold        integer     NOT NULL,
    justification_window_days integer     NOT NULL CHECK (justification_window_days BETWEEN 1 AND 30),
    -- NULL = las tardanzas no se convierten en falta; N = cada N tardanzas equivalen a 1 falta.
    tardies_per_absence       integer     CHECK (tardies_per_absence IS NULL OR tardies_per_absence BETWEEN 2 AND 10),
    updated_at                timestamptz NOT NULL,
    updated_by                uuid,
    version                   bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_attendance_policies_target CHECK (
        (scope = 'SUBJECT' AND subject_id IS NOT NULL AND program_id IS NULL)
        OR (scope <> 'SUBJECT' AND program_id IS NOT NULL AND subject_id IS NULL)),
    CONSTRAINT ck_attendance_policies_thresholds CHECK (
        warning_threshold >= 1 AND warning_threshold < critical_threshold
        AND critical_threshold < max_absences AND max_absences <= 50)
);
CREATE UNIQUE INDEX uq_attendance_policies_program ON attendance_policies (scope, program_id) WHERE scope <> 'SUBJECT';
CREATE UNIQUE INDEX uq_attendance_policies_subject ON attendance_policies (subject_id) WHERE scope = 'SUBJECT';

-- Política por defecto de cada programa: 4 faltas, aviso en la 2.ª, crítico en la 3.ª, 3 días hábiles.
INSERT INTO attendance_policies (id, scope, program_id, max_absences, warning_threshold, critical_threshold,
                                 justification_window_days, tardies_per_absence, updated_at)
SELECT ('0195f000-0001-7000-8000-00000000000' || right(p.code_rank::text, 1))::uuid, 'PROGRAM', p.id, 4, 2, 3, 3, NULL, now()
FROM (SELECT id, row_number() OVER (ORDER BY id) AS code_rank FROM programs) p;

-- ---------------------------------------------------------------- Estudiantes
-- Todo lo que no es identidad se enlaza por anon_code / id; la identidad vive aparte y cifrada.
CREATE TABLE students (
    id               uuid        PRIMARY KEY,
    anon_code        varchar(16) NOT NULL UNIQUE,
    program_id       uuid        NOT NULL REFERENCES programs (id),
    current_semester integer     NOT NULL CHECK (current_semester BETWEEN 1 AND 14),
    status           varchar(16) NOT NULL CHECK (status IN ('ACTIVO', 'RETIRADO', 'APLAZADO', 'GRADUADO')),
    admission_date   date        NOT NULL,
    user_id          uuid        UNIQUE REFERENCES users (id),
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    deleted_at       timestamptz,
    version          bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_students_program_status ON students (program_id, status, id) WHERE deleted_at IS NULL;

CREATE TABLE student_identities (
    student_id           uuid        PRIMARY KEY REFERENCES students (id),
    full_name_ciphertext text        NOT NULL,
    document_type        varchar(8)  NOT NULL CHECK (document_type IN ('CC', 'TI', 'CE', 'PA', 'PEP', 'PPT')),
    document_ciphertext  text        NOT NULL,
    document_blind_index varchar(64) NOT NULL UNIQUE,
    email_ciphertext     text,
    email_blind_index    varchar(64),
    phone_ciphertext     text,
    updated_at           timestamptz NOT NULL,
    version              bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_student_identities_email ON student_identities (email_blind_index);

-- Sin identificadores: solo variables de contexto (uso cuidadoso por sesgo, ver Fase 4).
CREATE TABLE socioeconomic_profiles (
    student_id     uuid        PRIMARY KEY REFERENCES students (id),
    stratum        integer     CHECK (stratum BETWEEN 1 AND 6),
    works          boolean,
    dependents     integer     CHECK (dependents BETWEEN 0 AND 20),
    transport_mode varchar(24) CHECK (transport_mode IN ('A_PIE', 'BICICLETA', 'TRANSPORTE_PUBLICO', 'MOTO', 'CARRO', 'OTRO')),
    updated_at     timestamptz NOT NULL,
    version        bigint      NOT NULL DEFAULT 0
);

CREATE TABLE enrollments (
    id           uuid        PRIMARY KEY,
    student_id   uuid        NOT NULL REFERENCES students (id),
    group_id     uuid        NOT NULL REFERENCES course_groups (id),
    term_id      uuid        NOT NULL REFERENCES terms (id),
    status       varchar(16) NOT NULL CHECK (status IN ('ACTIVA', 'RETIRADA')),
    enrolled_at  timestamptz NOT NULL,
    withdrawn_at timestamptz,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_enrollments_student_group UNIQUE (student_id, group_id)
);
-- estudiante + periodo (historial y portal) y grupo (listas de clase, cupo).
CREATE INDEX ix_enrollments_student_term ON enrollments (student_id, term_id);
CREATE INDEX ix_enrollments_group_status ON enrollments (group_id, status);

-- Historial de consentimiento: cada otorgamiento o revocación es una fila nueva.
CREATE TABLE data_consents (
    id             uuid        PRIMARY KEY,
    student_id     uuid        NOT NULL REFERENCES students (id),
    policy_version varchar(20) NOT NULL,
    purpose        varchar(40) NOT NULL CHECK (purpose IN ('TRATAMIENTO_ACADEMICO', 'ANALITICA_RETENCION', 'BIENESTAR', 'COMUNICACIONES')),
    granted        boolean     NOT NULL,
    channel        varchar(16) NOT NULL CHECK (channel IN ('PORTAL', 'IMPORTACION', 'PRESENCIAL')),
    recorded_at    timestamptz NOT NULL,
    recorded_by    uuid
);
CREATE INDEX ix_data_consents_student ON data_consents (student_id, purpose, recorded_at DESC);

-- ---------------------------------------------------------------- Importación masiva
CREATE TABLE import_jobs (
    id             uuid         PRIMARY KEY,
    import_type    varchar(16)  NOT NULL CHECK (import_type IN ('STUDENTS', 'TEACHERS', 'SUBJECTS', 'GROUPS', 'ENROLLMENTS')),
    file_format    varchar(8)   NOT NULL CHECK (file_format IN ('CSV', 'XLSX')),
    file_name      varchar(255),
    file_sha256    varchar(64)  NOT NULL,
    dry_run        boolean      NOT NULL,
    status         varchar(16)  NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    column_mapping jsonb,
    total_rows     integer      NOT NULL DEFAULT 0,
    created_rows   integer      NOT NULL DEFAULT 0,
    updated_rows   integer      NOT NULL DEFAULT 0,
    unchanged_rows integer      NOT NULL DEFAULT 0,
    valid_rows     integer      NOT NULL DEFAULT 0,
    error_rows     integer      NOT NULL DEFAULT 0,
    failure_reason varchar(500),
    requested_by   uuid,
    created_at     timestamptz  NOT NULL,
    started_at     timestamptz,
    finished_at    timestamptz,
    version        bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_import_jobs_status ON import_jobs (status, created_at);

-- Contenido del archivo (con datos personales): se borra al terminar el procesamiento.
CREATE TABLE import_files (
    job_id  uuid  PRIMARY KEY REFERENCES import_jobs (id) ON DELETE CASCADE,
    content bytea NOT NULL
);

-- Resultado por fila: nunca guarda valores, solo número de fila, resultado y mensaje.
CREATE TABLE import_row_results (
    job_id     uuid          NOT NULL REFERENCES import_jobs (id) ON DELETE CASCADE,
    row_number integer       NOT NULL,
    outcome    varchar(16)   NOT NULL CHECK (outcome IN ('CREATED', 'UPDATED', 'UNCHANGED', 'VALID', 'ERROR')),
    message    varchar(1000),
    PRIMARY KEY (job_id, row_number)
);

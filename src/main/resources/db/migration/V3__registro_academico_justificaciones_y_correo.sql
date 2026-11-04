-- =====================================================================================
-- V3: Registro académico, justificaciones, correo y bienestar (Fase 3)
-- Módulos: records (asistencia particionada por periodo, contadores de faltas, notas,
-- observaciones, causas de deserción), justifications (justificaciones y evidencias),
-- mailing (outbox de correo, supresiones) y wellbeing (evaluaciones y remisiones).
-- Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
-- =====================================================================================

-- ---------------------------------------------------------------- Asistencia (particionada por periodo)
-- Una fila por sesión e inscripción: una ausencia cuenta como UNA falta sin importar la duración.
CREATE TABLE attendance_records (
    id                     uuid        NOT NULL,
    term_id                uuid        NOT NULL REFERENCES terms (id),
    session_id             uuid        NOT NULL REFERENCES class_sessions (id),
    enrollment_id          uuid        NOT NULL REFERENCES enrollments (id),
    student_id             uuid        NOT NULL,
    group_id               uuid        NOT NULL,
    status                 varchar(12) NOT NULL CHECK (status IN ('PRESENTE', 'FALTA', 'TARDANZA')),
    justification_status   varchar(12) NOT NULL DEFAULT 'NINGUNA'
        CHECK (justification_status IN ('NINGUNA', 'PENDIENTE', 'APROBADA', 'RECHAZADA', 'VENCIDA')),
    justification_deadline date,
    recorded_by            uuid,
    recorded_at            timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0,
    PRIMARY KEY (id, term_id),
    CONSTRAINT uq_attendance_session_enrollment UNIQUE (term_id, session_id, enrollment_id)
) PARTITION BY LIST (term_id);

CREATE INDEX ix_attendance_enrollment ON attendance_records (term_id, enrollment_id);
CREATE INDEX ix_attendance_session ON attendance_records (term_id, session_id);
CREATE INDEX ix_attendance_student ON attendance_records (student_id, term_id);
CREATE INDEX ix_attendance_overdue ON attendance_records (justification_deadline)
    WHERE status = 'FALTA' AND justification_status = 'NINGUNA';

CREATE TABLE attendance_records_default PARTITION OF attendance_records DEFAULT;

-- Crea (idempotente) la partición de un periodo. La app la llama antes de la primera escritura
-- del periodo. SECURITY DEFINER: el rol de la app no necesita permiso de CREATE.
CREATE FUNCTION attendance_ensure_partition(p_term uuid) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = ${flyway:defaultSchema} AS
$$
DECLARE
    v_name text := 'attendance_records_' || replace(p_term::text, '-', '_');
BEGIN
    IF to_regclass(v_name) IS NULL THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(v_name, 0));
        IF to_regclass(v_name) IS NULL THEN
            EXECUTE format('CREATE TABLE %I PARTITION OF attendance_records FOR VALUES IN (%L)', v_name, p_term);
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appDbRole}') THEN
                EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO %I', v_name, '${appDbRole}');
            END IF;
        END IF;
    END IF;
    RETURN v_name;
END;
$$;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appDbRole}') AND '${appDbRole}' <> current_user THEN
        EXECUTE format('GRANT EXECUTE ON FUNCTION attendance_ensure_partition(uuid) TO %I', '${appDbRole}');
    END IF;
END;
$$;

-- Contador de faltas por inscripción. Se recalcula a partir de attendance_records en cada cambio.
CREATE TABLE absence_counters (
    enrollment_id    uuid        PRIMARY KEY REFERENCES enrollments (id),
    student_id       uuid        NOT NULL,
    group_id         uuid        NOT NULL,
    term_id          uuid        NOT NULL,
    unjustified      integer     NOT NULL DEFAULT 0,
    pending          integer     NOT NULL DEFAULT 0,
    justified        integer     NOT NULL DEFAULT 0,
    tardies          integer     NOT NULL DEFAULT 0,
    counted_absences integer     NOT NULL DEFAULT 0,
    max_absences     integer     NOT NULL,
    level            varchar(12) NOT NULL CHECK (level IN ('NINGUNO', 'AVISO', 'CRITICO', 'PERDIDA')),
    updated_at       timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0
);
CREATE INDEX ix_absence_counters_group ON absence_counters (group_id);
CREATE INDEX ix_absence_counters_student ON absence_counters (student_id, term_id);
CREATE INDEX ix_absence_counters_level ON absence_counters (term_id, level) WHERE level <> 'NINGUNO';

-- ---------------------------------------------------------------- Notas (sin cortes ni porcentajes)
CREATE TABLE grades (
    id            uuid         PRIMARY KEY,
    enrollment_id uuid         NOT NULL REFERENCES enrollments (id),
    student_id    uuid         NOT NULL,
    group_id      uuid         NOT NULL,
    term_id       uuid         NOT NULL,
    grade_value   numeric(2, 1) NOT NULL CHECK (grade_value BETWEEN 0.0 AND 5.0),
    assessed_on   date         NOT NULL,
    description   varchar(200),
    recorded_by   uuid,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    deleted_at    timestamptz,
    version       bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_grades_student_term ON grades (student_id, term_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_grades_group ON grades (group_id) WHERE deleted_at IS NULL;
-- Clave natural para la importación idempotente: inscripción + fecha + descripción.
CREATE UNIQUE INDEX uq_grades_natural ON grades (enrollment_id, assessed_on, coalesce(description, ''))
    WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------- Observaciones de docentes/tutores
CREATE TABLE observations (
    id               uuid        PRIMARY KEY,
    student_id       uuid        NOT NULL REFERENCES students (id),
    group_id         uuid,
    author_user_id   uuid        NOT NULL,
    observation_type varchar(12) NOT NULL CHECK (observation_type IN ('ACADEMICA', 'CONDUCTA')),
    text_ciphertext  text        NOT NULL,
    created_at       timestamptz NOT NULL,
    deleted_at       timestamptz
);
CREATE INDEX ix_observations_student ON observations (student_id, created_at DESC) WHERE deleted_at IS NULL;

-- ---------------------------------------------------------------- Causas de riesgo / retiro
CREATE TABLE dropout_causes (
    code     varchar(40)  PRIMARY KEY,
    name     varchar(150) NOT NULL,
    category varchar(16)  NOT NULL CHECK (category IN ('ACADEMICA', 'ECONOMICA', 'PERSONAL', 'SALUD', 'INSTITUCIONAL', 'OTRA')),
    active   boolean      NOT NULL DEFAULT true
);

INSERT INTO dropout_causes (code, name, category) VALUES
    ('BAJO_RENDIMIENTO', 'Bajo rendimiento académico', 'ACADEMICA'),
    ('PERDIDA_POR_INASISTENCIA', 'Pérdida de materias por inasistencia', 'ACADEMICA'),
    ('ORIENTACION_VOCACIONAL', 'Dudas de orientación vocacional', 'ACADEMICA'),
    ('DIFICULTAD_ECONOMICA', 'Dificultad económica para costear la matrícula', 'ECONOMICA'),
    ('NECESIDAD_DE_TRABAJAR', 'Necesidad de trabajar', 'ECONOMICA'),
    ('COSTO_TRANSPORTE', 'Costo o dificultad de transporte', 'ECONOMICA'),
    ('RESPONSABILIDADES_FAMILIARES', 'Responsabilidades familiares o cuidado de personas', 'PERSONAL'),
    ('CAMBIO_DE_RESIDENCIA', 'Cambio de ciudad o residencia', 'PERSONAL'),
    ('SALUD_FISICA', 'Situación de salud física', 'SALUD'),
    ('SALUD_MENTAL', 'Situación de salud mental', 'SALUD'),
    ('ADAPTACION_INSTITUCIONAL', 'Dificultad de adaptación a la vida universitaria', 'INSTITUCIONAL'),
    ('TRASLADO_OTRA_INSTITUCION', 'Traslado a otra institución', 'INSTITUCIONAL'),
    ('OTRA', 'Otra causa', 'OTRA');

CREATE TABLE student_causes (
    id               uuid        PRIMARY KEY,
    student_id       uuid        NOT NULL REFERENCES students (id),
    cause_code       varchar(40) NOT NULL REFERENCES dropout_causes (code),
    source           varchar(16) NOT NULL CHECK (source IN ('AUTORREPORTE', 'ENTREVISTA', 'DETECCION')),
    notes_ciphertext text,
    recorded_by      uuid,
    recorded_at      timestamptz NOT NULL,
    resolved_at      timestamptz
);
CREATE UNIQUE INDEX uq_student_causes_open ON student_causes (student_id, cause_code) WHERE resolved_at IS NULL;

-- ---------------------------------------------------------------- Justificaciones y evidencias
CREATE TABLE absence_justifications (
    id                        uuid        PRIMARY KEY,
    attendance_record_id      uuid        NOT NULL,
    term_id                   uuid        NOT NULL,
    enrollment_id             uuid        NOT NULL,
    student_id                uuid        NOT NULL,
    group_id                  uuid        NOT NULL,
    category                  varchar(12) NOT NULL CHECK (category IN ('SALUD', 'FAMILIAR', 'LABORAL', 'TRANSPORTE', 'OTRO')),
    description_ciphertext    text        NOT NULL,
    status                    varchar(12) NOT NULL CHECK (status IN ('PENDIENTE', 'APROBADA', 'RECHAZADA', 'VENCIDA')),
    deadline                  date        NOT NULL,
    assigned_teacher_id       uuid,
    reviewer_user_id          uuid,
    review_comment_ciphertext text,
    submitted_at              timestamptz NOT NULL,
    reviewed_at               timestamptz,
    updated_at                timestamptz NOT NULL,
    version                   bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_justification_record UNIQUE (attendance_record_id),
    CONSTRAINT fk_justification_attendance FOREIGN KEY (attendance_record_id, term_id)
        REFERENCES attendance_records (id, term_id)
);
CREATE INDEX ix_justifications_teacher ON absence_justifications (assigned_teacher_id, status);
CREATE INDEX ix_justifications_student ON absence_justifications (student_id, submitted_at DESC);
CREATE INDEX ix_justifications_group ON absence_justifications (group_id, status);

CREATE TABLE justification_evidences (
    id               uuid         PRIMARY KEY,
    justification_id uuid         NOT NULL REFERENCES absence_justifications (id),
    object_key       varchar(200) NOT NULL UNIQUE,
    media_type       varchar(40)  NOT NULL CHECK (media_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes       bigint       NOT NULL,
    sha256           varchar(64)  NOT NULL,
    scan_status      varchar(12)  NOT NULL CHECK (scan_status IN ('PENDIENTE', 'LIMPIO', 'INFECTADO', 'ERROR')),
    uploaded_by      uuid         NOT NULL,
    uploaded_at      timestamptz  NOT NULL,
    scanned_at       timestamptz
);
CREATE INDEX ix_evidences_justification ON justification_evidences (justification_id);
CREATE INDEX ix_evidences_pending_scan ON justification_evidences (uploaded_at) WHERE scan_status IN ('PENDIENTE', 'ERROR');

-- ---------------------------------------------------------------- Correo (outbox transaccional)
-- No se guarda el correo del destinatario: se resuelve al enviar. recipient_index = blind index.
CREATE TABLE email_outbox (
    id                  uuid         PRIMARY KEY,
    student_id          uuid         NOT NULL,
    template            varchar(40)  NOT NULL,
    dedupe_key          varchar(150) NOT NULL UNIQUE,
    payload             jsonb        NOT NULL,
    status              varchar(12)  NOT NULL
        CHECK (status IN ('PENDIENTE', 'ENVIANDO', 'ENVIADO', 'FALLIDO', 'CANCELADO', 'REBOTADO', 'SUPRIMIDO')),
    not_before          timestamptz  NOT NULL,
    next_attempt_at     timestamptz  NOT NULL,
    attempts            integer      NOT NULL DEFAULT 0,
    last_error          varchar(300),
    provider_message_id varchar(200),
    recipient_index     varchar(64),
    created_at          timestamptz  NOT NULL,
    sent_at             timestamptz,
    version             bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_email_outbox_due ON email_outbox (next_attempt_at) WHERE status = 'PENDIENTE';
CREATE INDEX ix_email_outbox_student ON email_outbox (student_id, created_at DESC);
CREATE INDEX ix_email_outbox_provider ON email_outbox (provider_message_id);

-- Direcciones que rebotaron: no se les vuelve a enviar hasta que se corrija el correo.
CREATE TABLE email_suppressions (
    recipient_index varchar(64) PRIMARY KEY,
    reason          varchar(100) NOT NULL,
    created_at      timestamptz  NOT NULL
);

-- ---------------------------------------------------------------- Bienestar (solo PSICOLOGO)
CREATE TABLE wellbeing_assessments (
    id                  uuid        PRIMARY KEY,
    student_id          uuid        NOT NULL REFERENCES students (id),
    assessment_type     varchar(12) NOT NULL CHECK (assessment_type IN ('FISICA', 'PSICOLOGICA')),
    summary_ciphertext  text        NOT NULL,
    findings_ciphertext text,
    assessed_by         uuid        NOT NULL,
    assessed_on         date        NOT NULL,
    created_at          timestamptz NOT NULL
);
CREATE INDEX ix_wellbeing_assessments_student ON wellbeing_assessments (student_id, assessed_on DESC);

CREATE TABLE wellbeing_referrals (
    id               uuid        PRIMARY KEY,
    student_id       uuid        NOT NULL REFERENCES students (id),
    assessment_id    uuid        REFERENCES wellbeing_assessments (id),
    service          varchar(20) NOT NULL CHECK (service IN ('PSICOLOGIA', 'MEDICINA', 'TRABAJO_SOCIAL', 'NUTRICION', 'EXTERNA')),
    reason_ciphertext text       NOT NULL,
    status           varchar(12) NOT NULL CHECK (status IN ('ABIERTA', 'ATENDIDA', 'CERRADA')),
    created_by       uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    closed_at        timestamptz
);
CREATE INDEX ix_wellbeing_referrals_student ON wellbeing_referrals (student_id, created_at DESC);

-- ---------------------------------------------------------------- Parámetros en caliente
INSERT INTO app_settings (setting_key, setting_value, description) VALUES
    ('attendance.edit-window-hours', '48',
     'Horas tras el fin de la sesión en que el docente puede editar la asistencia; después solo coordinador/admin'),
    ('mail.absence-mode', '"AFTER_EDIT_WINDOW"',
     'AFTER_EDIT_WINDOW: un correo por falta al cerrarse la ventana de edición; DAILY_DIGEST: un resumen diario'),
    ('mail.digest-hour', '7', 'Hora (Bogotá) de envío del resumen diario de faltas');

-- ---------------------------------------------------------------- Importación de notas
ALTER TABLE import_jobs DROP CONSTRAINT import_jobs_import_type_check;
ALTER TABLE import_jobs ADD CONSTRAINT import_jobs_import_type_check
    CHECK (import_type IN ('STUDENTS', 'TEACHERS', 'SUBJECTS', 'GROUPS', 'ENROLLMENTS', 'GRADES'));

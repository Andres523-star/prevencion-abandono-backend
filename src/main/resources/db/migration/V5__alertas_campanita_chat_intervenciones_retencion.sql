-- =====================================================================================
-- V5: Alertas, campanita, chat, intervenciones y retención (Fase 5)
-- Módulos: alerts, notifications, chat, interventions, retention. Enlace de cita del docente
-- (users) y nuevo origen de análisis INTERVENTION (risk).
-- Todo texto libre (mensajes, notas, encuestas, nombres de archivo) se cifra en columna.
-- Asistido por IA. Ver BITACORA_IA.md.
-- =====================================================================================

-- ---------------------------------------------------------------- Enlace de cita del docente
ALTER TABLE users ADD COLUMN appointment_link varchar(500);

-- ---------------------------------------------------------------- Reanálisis al resolver intervenciones
ALTER TABLE risk_assessments DROP CONSTRAINT risk_assessments_trigger_type_check;
ALTER TABLE risk_assessments ADD CONSTRAINT risk_assessments_trigger_type_check
    CHECK (trigger_type IN ('MANUAL', 'EVENT', 'THRESHOLD', 'BATCH', 'INTERVENTION'));

-- ---------------------------------------------------------------- Alertas
CREATE TABLE alerts (
    id                         uuid          PRIMARY KEY,
    student_id                 uuid          NOT NULL REFERENCES students (id),
    program_id                 uuid          NOT NULL,
    semester                   integer       NOT NULL,
    group_id                   uuid,
    kind                       varchar(12)   NOT NULL CHECK (kind IN ('RIESGO', 'ASISTENCIA', 'PERDIDA')),
    -- Una sola alerta abierta por clave (estudiante + tipo [+ grupo]): las repeticiones la actualizan.
    dedupe_key                 varchar(120)  NOT NULL,
    level                      varchar(5)    NOT NULL CHECK (level IN ('BAJO', 'MEDIO', 'ALTO')),
    attendance_level           varchar(8)    CHECK (attendance_level IN ('AVISO', 'CRITICO', 'PERDIDA')),
    score                      numeric(5, 2),
    score_type                 varchar(14),
    assessment_id              uuid,
    factors                    jsonb         NOT NULL DEFAULT '[]'::jsonb,
    suggested_actions          jsonb         NOT NULL DEFAULT '[]'::jsonb,
    -- Tiene factores de bienestar/salud: se asigna a PSICOLOGO y solo él ve esos factores.
    sensitive                  boolean       NOT NULL DEFAULT false,
    status                     varchar(10)   NOT NULL CHECK (status IN ('NUEVA', 'VISTA', 'ATENDIDA', 'DESCARTADA')),
    assigned_to                uuid,
    assigned_role              varchar(12)   CHECK (assigned_role IN ('CONSEJERO', 'PSICOLOGO', 'COORDINADOR')),
    escalation_level           integer       NOT NULL DEFAULT 0,
    escalated_at               timestamptz,
    resolution_note_ciphertext text,
    resolved_by                uuid,
    resolved_at                timestamptz,
    created_at                 timestamptz   NOT NULL,
    updated_at                 timestamptz   NOT NULL,
    version                    bigint        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_alerts_open ON alerts (dedupe_key) WHERE status IN ('NUEVA', 'VISTA');
CREATE INDEX ix_alerts_assignee ON alerts (assigned_to, status, id DESC);
CREATE INDEX ix_alerts_student ON alerts (student_id, id DESC);
CREATE INDEX ix_alerts_pending ON alerts (created_at) WHERE status IN ('NUEVA', 'VISTA');

-- ---------------------------------------------------------------- Campanita (solo en la app)
-- No se copia el nombre del estudiante: se resuelve al leer, con auditoría y ABAC.
CREATE TABLE notifications (
    id           uuid         PRIMARY KEY,
    recipient_id uuid         NOT NULL,
    type         varchar(28)  NOT NULL CHECK (type IN ('RIESGO_ESTUDIANTE', 'ALERTA_ASIGNADA', 'ALERTA_ESCALADA',
                                                       'JUSTIFICACION_PENDIENTE', 'JUSTIFICACION_DECIDIDA',
                                                       'NUEVO_MENSAJE', 'INTERVENCION_ASIGNADA',
                                                       'SEGUIMIENTO_RETENCION', 'CAMPANA_REINGRESO')),
    student_id   uuid,
    risk_level   varchar(5)   CHECK (risk_level IN ('BAJO', 'MEDIO', 'ALTO')),
    -- Motivo estructurado (códigos y valores académicos), nunca texto libre ni datos sensibles.
    reason       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    reason_hash  varchar(64)  NOT NULL,
    reference_id uuid,
    read_at      timestamptz,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL
);
CREATE INDEX ix_notifications_recipient ON notifications (recipient_id, id DESC);
CREATE INDEX ix_notifications_unread ON notifications (recipient_id) WHERE read_at IS NULL;
CREATE INDEX ix_notifications_dedupe ON notifications (recipient_id, type, student_id, created_at DESC);
CREATE INDEX ix_notifications_expiry ON notifications (expires_at);

-- ---------------------------------------------------------------- Chat docente-estudiante
CREATE TABLE chat_conversations (
    id               uuid        PRIMARY KEY,
    kind             varchar(24) NOT NULL CHECK (kind IN ('DOCENTE_ESTUDIANTE')),
    student_id       uuid        NOT NULL REFERENCES students (id),
    owner_user_id    uuid        NOT NULL,
    context_group_id uuid,
    -- Una sola conversación por par docente-estudiante.
    pair_key         varchar(80) NOT NULL UNIQUE,
    created_at       timestamptz NOT NULL,
    last_message_at  timestamptz
);

-- Participantes: permite sumar consejero o psicólogo después sin cambiar el modelo.
CREATE TABLE chat_participants (
    conversation_id uuid        NOT NULL REFERENCES chat_conversations (id),
    user_id         uuid        NOT NULL,
    role            varchar(12) NOT NULL CHECK (role IN ('DOCENTE', 'ESTUDIANTE', 'CONSEJERO', 'PSICOLOGO')),
    joined_at       timestamptz NOT NULL,
    last_read_at    timestamptz,
    PRIMARY KEY (conversation_id, user_id)
);
CREATE INDEX ix_chat_participants_user ON chat_participants (user_id);

CREATE TABLE chat_attachments (
    id                       uuid         PRIMARY KEY,
    conversation_id          uuid         NOT NULL REFERENCES chat_conversations (id),
    uploader_id              uuid         NOT NULL,
    object_key               varchar(200) NOT NULL UNIQUE,
    original_name_ciphertext text         NOT NULL,
    media_type               varchar(80)  NOT NULL,
    size_bytes               bigint       NOT NULL,
    duration_seconds         integer,
    sha256                   varchar(64)  NOT NULL,
    scan_status              varchar(12)  NOT NULL CHECK (scan_status IN ('PENDIENTE', 'LIMPIO', 'INFECTADO', 'ERROR')),
    message_id               uuid,
    created_at               timestamptz  NOT NULL,
    deleted_at               timestamptz
);

CREATE TABLE chat_messages (
    id              uuid        PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES chat_conversations (id),
    sender_id       uuid        NOT NULL,
    message_type    varchar(10) NOT NULL CHECK (message_type IN ('TEXTO', 'AUDIO', 'DOCUMENTO')),
    text_ciphertext text,
    attachment_id   uuid        REFERENCES chat_attachments (id),
    created_at      timestamptz NOT NULL,
    edited_at       timestamptz,
    deleted_at      timestamptz,
    purged_at       timestamptz
);
CREATE INDEX ix_chat_messages_page ON chat_messages (conversation_id, id DESC);
CREATE INDEX ix_chat_messages_retention ON chat_messages (created_at) WHERE purged_at IS NULL;

CREATE TABLE chat_message_reports (
    id                uuid        PRIMARY KEY,
    message_id        uuid        NOT NULL REFERENCES chat_messages (id),
    reporter_id       uuid        NOT NULL,
    reason_ciphertext text        NOT NULL,
    status            varchar(10) NOT NULL CHECK (status IN ('ABIERTO', 'REVISADO')),
    created_at        timestamptz NOT NULL
);

-- ---------------------------------------------------------------- Intervenciones
CREATE TABLE interventions (
    id                       uuid         PRIMARY KEY,
    student_id               uuid         NOT NULL REFERENCES students (id),
    type                     varchar(16)  NOT NULL CHECK (type IN ('TUTORIA', 'PSICOLOGIA', 'BECA', 'REUNION_PADRES',
                                                                   'FLEXIBILIZACION', 'AYUDA_ECONOMICA', 'OTRO')),
    status                   varchar(12)  NOT NULL CHECK (status IN ('PENDIENTE', 'EN_PROCESO', 'RESUELTA')),
    title                    varchar(200) NOT NULL,
    description_ciphertext   text,
    responsible_user_id      uuid,
    responsible_role         varchar(12),
    due_date                 date,
    result_ciphertext        text,
    alert_id                 uuid,
    plan_id                  uuid,
    retention_case_id        uuid,
    risk_before_assessment   uuid,
    risk_before_level        varchar(5),
    risk_before_score        numeric(5, 2),
    risk_after_assessment    uuid,
    risk_after_level         varchar(5),
    risk_after_score         numeric(5, 2),
    created_by               uuid         NOT NULL,
    created_at               timestamptz  NOT NULL,
    updated_at               timestamptz  NOT NULL,
    resolved_at              timestamptz,
    version                  bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_interventions_student ON interventions (student_id, created_at DESC);
CREATE INDEX ix_interventions_responsible ON interventions (responsible_user_id, status);

-- restricted = solo PSICOLOGO (notas de psicología).
CREATE TABLE intervention_notes (
    id              uuid        PRIMARY KEY,
    intervention_id uuid        NOT NULL REFERENCES interventions (id),
    author_id       uuid        NOT NULL,
    restricted      boolean     NOT NULL,
    text_ciphertext text        NOT NULL,
    created_at      timestamptz NOT NULL
);
CREATE INDEX ix_intervention_notes ON intervention_notes (intervention_id, created_at);

-- ---------------------------------------------------------------- Retención
CREATE TABLE retention_cases (
    id                     uuid        PRIMARY KEY,
    student_id             uuid        NOT NULL REFERENCES students (id),
    program_id             uuid        NOT NULL,
    term_id                uuid,
    stage                  varchar(14) NOT NULL CHECK (stage IN ('DETECTADO', 'CONTACTADO', 'ENTREVISTA', 'PLAN_DE_APOYO',
                                                                 'SEGUIMIENTO', 'CERRADO')),
    origin                 varchar(12) NOT NULL CHECK (origin IN ('AUTOMATICO', 'AUTORREPORTE', 'CONSEJERO')),
    assigned_counselor_id  uuid,
    outcome                varchar(12) CHECK (outcome IN ('RETENIDO', 'APLAZADO', 'TRANSFERIDO', 'RETIRADO')),
    exit_cause_code        varchar(40),
    exit_survey_ciphertext text,
    opened_by              uuid,
    opened_at              timestamptz NOT NULL,
    contacted_at           timestamptz,
    closed_at              timestamptz,
    updated_at             timestamptz NOT NULL,
    version                bigint      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_retention_open ON retention_cases (student_id) WHERE stage <> 'CERRADO';
CREATE INDEX ix_retention_program ON retention_cases (program_id, opened_at);

CREATE TABLE retention_contacts (
    id               uuid        PRIMARY KEY,
    case_id          uuid        NOT NULL REFERENCES retention_cases (id),
    channel          varchar(12) NOT NULL CHECK (channel IN ('TELEFONO', 'CORREO', 'CHAT', 'PRESENCIAL', 'OTRO')),
    result           varchar(12) NOT NULL CHECK (result IN ('CONTACTADO', 'NO_CONTESTA', 'RECHAZA', 'OTRO')),
    notes_ciphertext text,
    attempted_by     uuid        NOT NULL,
    attempted_at     timestamptz NOT NULL
);

CREATE TABLE retention_interview_notes (
    id              uuid        PRIMARY KEY,
    case_id         uuid        NOT NULL REFERENCES retention_cases (id),
    author_id       uuid        NOT NULL,
    text_ciphertext text        NOT NULL,
    created_at      timestamptz NOT NULL
);

CREATE TABLE retention_tasks (
    id         uuid         PRIMARY KEY,
    case_id    uuid         NOT NULL REFERENCES retention_cases (id),
    title      varchar(200) NOT NULL,
    due_date   date         NOT NULL,
    done_at    timestamptz,
    created_by uuid         NOT NULL,
    created_at timestamptz  NOT NULL
);

CREATE TABLE retention_follow_ups (
    id          uuid        PRIMARY KEY,
    case_id     uuid        NOT NULL REFERENCES retention_cases (id),
    kind        varchar(16) NOT NULL CHECK (kind IN ('SEGUIMIENTO', 'REINGRESO')),
    offset_days integer     NOT NULL,
    due_on      date        NOT NULL,
    status      varchar(10) NOT NULL CHECK (status IN ('PENDIENTE', 'HECHO', 'CANCELADO')),
    reminded_at timestamptz,
    done_at     timestamptz
);
CREATE INDEX ix_followups_due ON retention_follow_ups (due_on) WHERE status = 'PENDIENTE';

CREATE TABLE retention_campaigns (
    id           uuid        PRIMARY KEY,
    status       varchar(10) NOT NULL CHECK (status IN ('BORRADOR', 'APROBADA', 'DESCARTADA')),
    targets      jsonb       NOT NULL,
    target_count integer     NOT NULL,
    created_by   uuid,
    created_at   timestamptz NOT NULL,
    reviewed_by  uuid,
    reviewed_at  timestamptz
);

-- ---------------------------------------------------------------- Parámetros en caliente
INSERT INTO app_settings (setting_key, setting_value, description) VALUES
    ('alerts.escalation-hours', '48', 'Horas sin atender tras las que una alerta se escala a la coordinación del programa'),
    ('notifications.expiry-days', '30', 'Días tras los que una notificación de la campanita vence y se depura'),
    ('notifications.dedupe-hours', '72', 'Ventana en la que no se repite la misma notificación (mismo estudiante, nivel y motivo)'),
    ('chat.retention-days', '730', 'Días que se conserva el contenido de los mensajes y adjuntos del chat'),
    ('chat.max-message-length', '4000', 'Longitud máxima de un mensaje de texto del chat'),
    ('chat.attachment-max-mb', '10', 'Tamaño máximo de un adjunto del chat (MB)'),
    ('chat.audio-max-seconds', '300', 'Duración máxima de una nota de audio del chat (segundos)'),
    ('users.appointment-link-domains',
     '["calendar.google.com", "calendar.app.google", "outlook.office.com", "outlook.office365.com", "outlook.live.com", "bookings.office.com"]',
     'Dominios permitidos para el enlace de citas del docente (solo https)'),
    ('retention.auto-open-level', '"ALTO"', 'Nivel de riesgo desde el que se abre automáticamente un caso de retención'),
    ('retention.reentry-after-days', '30', 'Días tras el cierre (retirado o aplazado) para incluir el caso en la campaña de reingreso');

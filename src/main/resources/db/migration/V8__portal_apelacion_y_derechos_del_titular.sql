-- Fase 7: apelación de justificaciones, solicitudes del titular (Ley 1581) y avisos nuevos de la campanita.

-- --------------------------------------------------------------- Apelación de una justificación rechazada
-- Una sola apelación por justificación; la decide el mismo docente de la materia.
ALTER TABLE absence_justifications DROP CONSTRAINT absence_justifications_status_check;
ALTER TABLE absence_justifications ADD CONSTRAINT absence_justifications_status_check
    CHECK (status IN ('PENDIENTE', 'APROBADA', 'RECHAZADA', 'VENCIDA', 'APELADA'));
ALTER TABLE absence_justifications ADD COLUMN appeal_text_ciphertext text;
ALTER TABLE absence_justifications ADD COLUMN appealed_at timestamptz;

-- --------------------------------------------------------------- Solicitudes del titular
-- Consulta (acceso), rectificación y supresión. Plazos de la Ley 1581: consultas 10 días hábiles,
-- reclamos (rectificación y supresión) 15 días hábiles.
CREATE TABLE data_subject_requests (
    id                          uuid         PRIMARY KEY,
    student_id                  uuid         NOT NULL REFERENCES students (id),
    requested_by                uuid         NOT NULL,
    request_type                varchar(16)  NOT NULL CHECK (request_type IN ('ACCESO', 'RECTIFICACION', 'SUPRESION')),
    detail_ciphertext           text,
    status                      varchar(12)  NOT NULL CHECK (status IN ('RADICADA', 'APROBADA', 'RECHAZADA', 'EJECUTADA')),
    due_on                      date         NOT NULL,
    decided_by                  uuid,
    decision_comment_ciphertext text,
    decided_at                  timestamptz,
    executed_by                 uuid,
    executed_at                 timestamptz,
    export_object_key           varchar(200),
    export_expires_at           timestamptz,
    created_at                  timestamptz  NOT NULL,
    updated_at                  timestamptz  NOT NULL,
    version                     bigint       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_data_requests_open ON data_subject_requests (student_id, request_type)
    WHERE status IN ('RADICADA', 'APROBADA');
CREATE INDEX ix_data_requests_queue ON data_subject_requests (due_on) WHERE status IN ('RADICADA', 'APROBADA');
CREATE INDEX ix_data_requests_student ON data_subject_requests (student_id, created_at DESC);
CREATE INDEX ix_data_requests_exports ON data_subject_requests (export_expires_at) WHERE export_object_key IS NOT NULL;

-- --------------------------------------------------------------- Campanita
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('RIESGO_ESTUDIANTE', 'ALERTA_ASIGNADA', 'ALERTA_ESCALADA', 'JUSTIFICACION_PENDIENTE',
                    'JUSTIFICACION_DECIDIDA', 'JUSTIFICACION_APELADA', 'NUEVO_MENSAJE', 'INTERVENCION_ASIGNADA',
                    'SEGUIMIENTO_RETENCION', 'CAMPANA_REINGRESO', 'SOLICITUD_DATOS', 'SOLICITUD_DATOS_RESPONDIDA'));

-- --------------------------------------------------------------- Parámetros en caliente
INSERT INTO app_settings (setting_key, setting_value, description) VALUES
    ('justifications.appeal-days', '5', 'Días calendario para apelar una justificación rechazada'),
    ('privacy.access-response-business-days', '10', 'Plazo de respuesta de una consulta del titular (Ley 1581, art. 14)'),
    ('privacy.claim-response-business-days', '15', 'Plazo de respuesta de un reclamo: rectificación o supresión (Ley 1581, art. 15)'),
    ('privacy.export-expiry-hours', '72', 'Horas que la copia de los datos del titular queda disponible para descargar'),
    ('mail.outbox-retention-days', '180', 'Días que se conservan los avisos de correo ya enviados, cancelados o fallidos'),
    ('ai.calls-retention-days', '365', 'Días que se conserva el registro técnico de llamadas a la IA (sin contenido)');

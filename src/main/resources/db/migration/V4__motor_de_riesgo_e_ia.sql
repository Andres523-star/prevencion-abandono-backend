-- =====================================================================================
-- V4: Motor de riesgo e IA (Fase 4)
-- Módulos: risk (puntaje por reglas o modelo ML, historial de análisis, estado por estudiante,
-- planes de apoyo, registro de modelos y exportaciones del dataset) y ai (catálogo de recursos
-- de apoyo, consumo de tokens, bitácora de llamadas y agente de retención).
-- Spring Batch (lote nocturno) guarda aquí su JobRepository.
-- Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
-- =====================================================================================

-- ---------------------------------------------------------------- Catálogo de recursos de apoyo (RAG simple)
-- El id es un código estable: la IA solo puede recomendar recursos por este id.
CREATE TABLE support_resources (
    id               varchar(40)   PRIMARY KEY CHECK (id ~ '^[A-Z0-9]+(-[A-Z0-9]+)*$'),
    name             varchar(150)  NOT NULL,
    category         varchar(24)   NOT NULL CHECK (category IN ('TUTORIA', 'MONITORIA', 'NIVELACION', 'BECA',
                                                                 'APOYO_PSICOLOGICO', 'BIENESTAR', 'TRANSPORTE',
                                                                 'ALIMENTACION', 'FLEXIBILIZACION',
                                                                 'ASESORIA_FINANCIERA', 'CONSEJERIA', 'OTRO')),
    description      varchar(1000) NOT NULL,
    -- Reglas de elegibilidad evaluadas en el backend (el estrato nunca sale hacia la IA).
    eligibility      jsonb         NOT NULL DEFAULT '{}'::jsonb,
    eligibility_text varchar(500)  NOT NULL,
    -- Contacto institucional (oficina, extensión, correo de dependencia); nunca personal.
    contact          varchar(200)  NOT NULL,
    active           boolean       NOT NULL DEFAULT true,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL DEFAULT 0
);

INSERT INTO support_resources (id, name, category, description, eligibility, eligibility_text, contact, created_at, updated_at) VALUES
    ('TUT-ACADEMICA', 'Tutorías académicas', 'TUTORIA',
     'Sesiones individuales o grupales con docentes tutores para reforzar temas de una materia.',
     '{}', 'Todos los estudiantes activos.', 'Vicerrectoría Académica - tutorias@universidad.edu.co', now(), now()),
    ('MON-PARES', 'Monitorías entre pares', 'MONITORIA',
     'Estudiantes de semestres avanzados acompañan la preparación de evaluaciones y talleres.',
     '{}', 'Todos los estudiantes activos.', 'Coordinación de monitorías - ext. 2101', now(), now()),
    ('NIV-CIENCIAS-BASICAS', 'Cursos de nivelación en ciencias básicas', 'NIVELACION',
     'Cursos cortos de matemáticas, química y biología para cerrar brechas de conocimientos previos.',
     '{"programs": ["ENF", "MED", "ODO", "ISW", "IIN"], "maxSemester": 4}',
     'Programas de salud e ingeniería, semestres 1 a 4.', 'Departamento de Ciencias Básicas - ext. 2150', now(), now()),
    ('BECA-SOSTENIMIENTO', 'Beca de sostenimiento', 'BECA',
     'Apoyo económico semestral para gastos de sostenimiento, sujeto a convocatoria.',
     '{"maxStratum": 3}', 'Estratos 1 a 3, sujeto a convocatoria y cupos.', 'Bienestar Universitario - becas@universidad.edu.co', now(), now()),
    ('APOYO-PSICOLOGICO', 'Apoyo psicológico', 'APOYO_PSICOLOGICO',
     'Atención psicológica individual confidencial con el equipo de bienestar.',
     '{}', 'Todos los estudiantes activos, por remisión o solicitud propia.', 'Bienestar Universitario - ext. 2200', now(), now()),
    ('BIENESTAR-ACOMPANAMIENTO', 'Acompañamiento integral de bienestar', 'BIENESTAR',
     'Seguimiento de trabajo social para situaciones familiares, de adaptación o de salud.',
     '{}', 'Todos los estudiantes activos.', 'Bienestar Universitario - ext. 2210', now(), now()),
    ('AUX-TRANSPORTE', 'Auxilio de transporte', 'TRANSPORTE',
     'Subsidio parcial del transporte público para estudiantes con dificultades de desplazamiento.',
     '{"maxStratum": 3, "transportModes": ["TRANSPORTE_PUBLICO", "OTRO"]}',
     'Estratos 1 a 3 que usan transporte público.', 'Bienestar Universitario - ext. 2220', now(), now()),
    ('AUX-ALIMENTACION', 'Apoyo alimentario', 'ALIMENTACION',
     'Almuerzo subsidiado en la cafetería universitaria durante el semestre.',
     '{"maxStratum": 3}', 'Estratos 1 a 3, sujeto a cupos.', 'Bienestar Universitario - ext. 2230', now(), now()),
    ('FLEX-ACADEMICA', 'Flexibilización académica', 'FLEXIBILIZACION',
     'Ajuste de horario, carga académica o cambio de grupo cuando hay cruces con trabajo o cuidado familiar.',
     '{}', 'Todos los estudiantes activos, con aprobación de la coordinación del programa.', 'Coordinación del programa', now(), now()),
    ('ASESORIA-FINANCIERA', 'Asesoría financiera y crédito educativo', 'ASESORIA_FINANCIERA',
     'Orientación sobre financiación de matrícula, créditos y acuerdos de pago.',
     '{}', 'Todos los estudiantes activos.', 'Oficina de Crédito Educativo - ext. 2300', now(), now()),
    ('CONSEJERIA-ACADEMICA', 'Consejería académica', 'CONSEJERIA',
     'Reunión con el consejero académico para revisar el plan de estudios y las opciones del estudiante.',
     '{}', 'Todos los estudiantes activos.', 'Consejería académica del programa', now(), now());

-- ---------------------------------------------------------------- Historial de análisis de riesgo
-- Cada análisis es una fila nueva (append): permite ver la evolución del riesgo.
CREATE TABLE risk_assessments (
    id                uuid         PRIMARY KEY,
    student_id        uuid         NOT NULL REFERENCES students (id),
    term_id           uuid,
    level             varchar(5)   NOT NULL CHECK (level IN ('BAJO', 'MEDIO', 'ALTO')),
    scorer_level      varchar(5)   NOT NULL CHECK (scorer_level IN ('BAJO', 'MEDIO', 'ALTO')),
    ai_level          varchar(5)   CHECK (ai_level IN ('BAJO', 'MEDIO', 'ALTO')),
    score             numeric(5, 2) NOT NULL CHECK (score BETWEEN 0 AND 100),
    score_type        varchar(14)  NOT NULL CHECK (score_type IN ('RULES', 'ML_CALIBRATED')),
    probability       numeric(5, 4) CHECK (probability BETWEEN 0 AND 1),
    source            varchar(6)   NOT NULL CHECK (source IN ('RULES', 'AI', 'HYBRID')),
    trigger_type      varchar(14)  NOT NULL CHECK (trigger_type IN ('MANUAL', 'EVENT', 'THRESHOLD', 'BATCH')),
    factors           jsonb        NOT NULL,
    ai_summary        text,
    ai_factors        jsonb        NOT NULL DEFAULT '[]'::jsonb,
    recommendations   jsonb        NOT NULL DEFAULT '[]'::jsonb,
    probable_causes   jsonb        NOT NULL DEFAULT '[]'::jsonb,
    confidence        numeric(3, 2) CHECK (confidence BETWEEN 0 AND 1),
    -- La IA recibió señales sensibles (bienestar): su texto no se muestra a docentes ni coordinadores.
    sensitive_context boolean      NOT NULL DEFAULT false,
    rules_version     varchar(64)  NOT NULL,
    ml_model_version  varchar(60),
    ai_model          varchar(80),
    prompt_version    varchar(40),
    payload_hash      varchar(64)     NOT NULL,
    input_tokens      integer      NOT NULL DEFAULT 0,
    output_tokens     integer      NOT NULL DEFAULT 0,
    ai_attempts       integer      NOT NULL DEFAULT 0,
    fallback_reason   varchar(30),
    -- Solo para métricas de sesgo; el estrato nunca entra al puntaje ni a la IA.
    program_id        uuid         NOT NULL,
    semester          integer      NOT NULL,
    stratum           integer,
    requested_by      uuid,
    created_at        timestamptz  NOT NULL
);
CREATE INDEX ix_risk_assessments_student ON risk_assessments (student_id, created_at DESC, id DESC);
CREATE INDEX ix_risk_assessments_created ON risk_assessments (created_at);

-- Último estado por estudiante: detección de cambios (hash del payload) y vigilancia.
CREATE TABLE risk_student_state (
    student_id         uuid         PRIMARY KEY REFERENCES students (id),
    last_assessment_id uuid,
    last_level         varchar(5)   CHECK (last_level IN ('BAJO', 'MEDIO', 'ALTO')),
    last_score         numeric(5, 2),
    last_payload_hash  varchar(64),
    last_assessed_at   timestamptz,
    program_id         uuid,
    semester           integer,
    stratum            integer,
    watch_until        timestamptz,
    watch_reason       varchar(40),
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_risk_state_watch ON risk_student_state (watch_until) WHERE watch_until IS NOT NULL;
CREATE INDEX ix_risk_state_program ON risk_student_state (program_id, last_level);

-- Cola de trabajo del motor: los eventos (notas, faltas, umbrales) solo encolan; un worker analiza
-- fuera de la petición del docente. ANALYZE tiene antirrebote (una fila por estudiante).
CREATE TABLE risk_work_queue (
    id               uuid        PRIMARY KEY,
    student_id       uuid        NOT NULL REFERENCES students (id),
    kind             varchar(14) NOT NULL CHECK (kind IN ('ANALYZE', 'SUPPORT_PLAN')),
    trigger_type     varchar(14) NOT NULL CHECK (trigger_type IN ('EVENT', 'THRESHOLD')),
    enrollment_id    uuid,
    group_id         uuid,
    attendance_level varchar(8)  CHECK (attendance_level IN ('AVISO', 'CRITICO', 'PERDIDA')),
    not_before       timestamptz NOT NULL,
    attempts         integer     NOT NULL DEFAULT 0,
    last_error       varchar(200),
    created_at       timestamptz NOT NULL
);
CREATE UNIQUE INDEX uq_risk_queue_analyze ON risk_work_queue (student_id) WHERE kind = 'ANALYZE';
CREATE UNIQUE INDEX uq_risk_queue_plan ON risk_work_queue (enrollment_id, attendance_level) WHERE kind = 'SUPPORT_PLAN';
CREATE INDEX ix_risk_queue_due ON risk_work_queue (not_before);

-- ---------------------------------------------------------------- Planes de apoyo por asistencia
-- Propuesta para que el estudiante no pierda la materia; un humano la aprueba o la rechaza.
CREATE TABLE support_plans (
    id                        uuid         PRIMARY KEY,
    student_id                uuid         NOT NULL REFERENCES students (id),
    enrollment_id             uuid         NOT NULL REFERENCES enrollments (id),
    group_id                  uuid         NOT NULL,
    trigger_level             varchar(8)   NOT NULL CHECK (trigger_level IN ('AVISO', 'CRITICO', 'PERDIDA')),
    status                    varchar(12)  NOT NULL CHECK (status IN ('PROPUESTO', 'APROBADO', 'RECHAZADO', 'REEMPLAZADO')),
    source                    varchar(6)   NOT NULL CHECK (source IN ('RULES', 'AI')),
    actions                   jsonb        NOT NULL,
    -- Texto para el estudiante con marcadores ({{nombre}}, {{materia}}); el backend los rellena al leer.
    student_message           text         NOT NULL,
    ai_model                  varchar(80),
    prompt_version            varchar(40),
    payload_hash              varchar(64),
    fallback_reason           varchar(30),
    created_at                timestamptz  NOT NULL,
    reviewed_by               uuid,
    reviewed_at               timestamptz,
    review_comment_ciphertext text,
    version                   bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_support_plans_student ON support_plans (student_id, created_at DESC);
CREATE UNIQUE INDEX uq_support_plans_open ON support_plans (enrollment_id) WHERE status = 'PROPUESTO';

-- ---------------------------------------------------------------- Consumo y bitácora de la IA
CREATE TABLE ai_token_usage (
    usage_date    date        PRIMARY KEY,
    input_tokens  bigint      NOT NULL DEFAULT 0,
    output_tokens bigint      NOT NULL DEFAULT 0,
    calls         integer     NOT NULL DEFAULT 0,
    updated_at    timestamptz NOT NULL
);

-- Sin contenido: solo modelo, versión del prompt, hash del payload y resultado.
CREATE TABLE ai_calls (
    id             uuid        PRIMARY KEY,
    purpose        varchar(20) NOT NULL,
    model          varchar(80) NOT NULL,
    prompt_version varchar(40) NOT NULL,
    payload_hash   varchar(64)    NOT NULL,
    outcome        varchar(20) NOT NULL,
    attempts       integer     NOT NULL,
    input_tokens   integer     NOT NULL DEFAULT 0,
    output_tokens  integer     NOT NULL DEFAULT 0,
    latency_ms     integer     NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL
);
CREATE INDEX ix_ai_calls_created ON ai_calls (created_at);

-- Agente de retención: solo produce borradores para revisión humana.
CREATE TABLE agent_runs (
    id             uuid        PRIMARY KEY,
    student_id     uuid        NOT NULL REFERENCES students (id),
    status         varchar(12) NOT NULL CHECK (status IN ('EN_CURSO', 'BORRADOR', 'FALLIDO')),
    model          varchar(80) NOT NULL,
    prompt_version varchar(40) NOT NULL,
    draft          jsonb,
    tool_calls     integer     NOT NULL DEFAULT 0,
    input_tokens   integer     NOT NULL DEFAULT 0,
    output_tokens  integer     NOT NULL DEFAULT 0,
    failure_reason varchar(40),
    requested_by   uuid        NOT NULL,
    created_at     timestamptz NOT NULL,
    finished_at    timestamptz
);
CREATE INDEX ix_agent_runs_student ON agent_runs (student_id, created_at DESC);

CREATE TABLE agent_tool_calls (
    id          uuid        PRIMARY KEY,
    run_id      uuid        NOT NULL REFERENCES agent_runs (id),
    seq         integer     NOT NULL,
    tool_name   varchar(60) NOT NULL,
    tool_input  jsonb       NOT NULL,
    output_hash varchar(64)    NOT NULL,
    output_size integer     NOT NULL,
    is_error    boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL,
    CONSTRAINT uq_agent_tool_calls UNIQUE (run_id, seq)
);

-- ---------------------------------------------------------------- Modelo predictivo propio (ONNX)
CREATE TABLE ml_models (
    id              uuid         PRIMARY KEY,
    model_version   varchar(60)  NOT NULL UNIQUE CHECK (model_version ~ '^[a-z0-9]+([._-][a-z0-9]+)*$'),
    algorithm       varchar(60)  NOT NULL,
    trained_at      timestamptz  NOT NULL,
    training_terms  jsonb        NOT NULL,
    test_terms      jsonb        NOT NULL,
    features        jsonb        NOT NULL,
    metrics         jsonb        NOT NULL,
    output_name     varchar(60)  NOT NULL,
    artifact_key    varchar(200) NOT NULL,
    artifact_sha256 varchar(64)     NOT NULL,
    status          varchar(12)  NOT NULL CHECK (status IN ('REGISTRADO', 'APROBADO', 'RETIRADO')),
    comparison      jsonb,
    approved_by     uuid,
    approved_at     timestamptz,
    registered_by   uuid         NOT NULL,
    created_at      timestamptz  NOT NULL,
    lock_version    bigint       NOT NULL DEFAULT 0
);

CREATE TABLE ml_dataset_exports (
    id           uuid         PRIMARY KEY,
    requested_by uuid         NOT NULL,
    reason       varchar(300) NOT NULL,
    terms        jsonb        NOT NULL,
    row_count    integer      NOT NULL,
    sha256       varchar(64)     NOT NULL,
    created_at   timestamptz  NOT NULL
);

-- ---------------------------------------------------------------- Spring Batch (JobRepository)
-- Copia de org/springframework/batch/core/schema-postgresql.sql (Spring Batch 5.2).
CREATE TABLE BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT       NOT NULL PRIMARY KEY,
    VERSION         BIGINT,
    JOB_NAME        VARCHAR(100) NOT NULL,
    JOB_KEY         VARCHAR(32)  NOT NULL,
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT        NOT NULL PRIMARY KEY,
    VERSION          BIGINT,
    JOB_INSTANCE_ID  BIGINT        NOT NULL,
    CREATE_TIME      TIMESTAMP     NOT NULL,
    START_TIME       TIMESTAMP DEFAULT NULL,
    END_TIME         TIMESTAMP DEFAULT NULL,
    STATUS           VARCHAR(10),
    EXIT_CODE        VARCHAR(2500),
    EXIT_MESSAGE     VARCHAR(2500),
    LAST_UPDATED     TIMESTAMP,
    CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID) REFERENCES BATCH_JOB_INSTANCE (JOB_INSTANCE_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT        NOT NULL,
    PARAMETER_NAME   VARCHAR(100)  NOT NULL,
    PARAMETER_TYPE   VARCHAR(100)  NOT NULL,
    PARAMETER_VALUE  VARCHAR(2500),
    IDENTIFYING      CHAR(1)       NOT NULL,
    CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID) REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID  BIGINT        NOT NULL PRIMARY KEY,
    VERSION            BIGINT        NOT NULL,
    STEP_NAME          VARCHAR(100)  NOT NULL,
    JOB_EXECUTION_ID   BIGINT        NOT NULL,
    CREATE_TIME        TIMESTAMP     NOT NULL,
    START_TIME         TIMESTAMP DEFAULT NULL,
    END_TIME           TIMESTAMP DEFAULT NULL,
    STATUS             VARCHAR(10),
    COMMIT_COUNT       BIGINT,
    READ_COUNT         BIGINT,
    FILTER_COUNT       BIGINT,
    WRITE_COUNT        BIGINT,
    READ_SKIP_COUNT    BIGINT,
    WRITE_SKIP_COUNT   BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT     BIGINT,
    EXIT_CODE          VARCHAR(2500),
    EXIT_MESSAGE       VARCHAR(2500),
    LAST_UPDATED       TIMESTAMP,
    CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID) REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID  BIGINT        NOT NULL PRIMARY KEY,
    SHORT_CONTEXT      VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID) REFERENCES BATCH_STEP_EXECUTION (STEP_EXECUTION_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID   BIGINT        NOT NULL PRIMARY KEY,
    SHORT_CONTEXT      VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID) REFERENCES BATCH_JOB_EXECUTION (JOB_EXECUTION_ID)
);

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

-- Las tablas nuevas reciben permisos por ALTER DEFAULT PRIVILEGES (V1); las secuencias no.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appDbRole}') AND '${appDbRole}' <> current_user THEN
        EXECUTE format('GRANT USAGE, SELECT, UPDATE ON SEQUENCE batch_step_execution_seq, batch_job_execution_seq, batch_job_seq TO %I',
                       '${appDbRole}');
        -- El historial de análisis es append-only para la app.
        EXECUTE format('REVOKE UPDATE, DELETE ON risk_assessments FROM %I', '${appDbRole}');
        EXECUTE format('REVOKE UPDATE, DELETE ON agent_tool_calls FROM %I', '${appDbRole}');
    END IF;
END;
$$;

-- ---------------------------------------------------------------- Parámetros en caliente
-- Cada regla del motor es un objeto JSON editable por ADMIN sin redeploy (PUT /api/v1/settings/{clave}).
INSERT INTO app_settings (setting_key, setting_value, description) VALUES
    ('risk.levels', '{"medium": 30, "high": 60}',
     'Puntaje de reglas (0-100) desde el que el riesgo es MEDIO y ALTO'),
    ('risk.rules.low-average',
     '{"enabled": true, "threshold": 3.0, "severeThreshold": 2.5, "points": 15, "severePoints": 25}',
     'Promedio del semestre bajo: puntos si es menor al umbral y más puntos si es menor al umbral severo'),
    ('risk.rules.failing-subjects', '{"enabled": true, "pointsPerSubject": 10, "maxPoints": 30}',
     'Materias con promedio menor a 3.0'),
    ('risk.rules.absences',
     '{"enabled": true, "warningPoints": 8, "criticalPoints": 15, "lostPoints": 25, "maxPoints": 40}',
     'Faltas injustificadas según el nivel alcanzado en la política de cada materia (aviso, crítico, pérdida)'),
    ('risk.rules.tardies', '{"enabled": true, "threshold": 3, "points": 5}',
     'Tardanzas acumuladas en el periodo'),
    ('risk.rules.grade-trend', '{"enabled": true, "pointsPerSubject": 5, "maxPoints": 15}',
     'Materias con tendencia de notas a la baja'),
    ('risk.rules.conduct', '{"enabled": true, "threshold": 1, "pointsPerObservation": 4, "maxPoints": 12}',
     'Observaciones de conducta en el periodo'),
    ('risk.rules.wellbeing', '{"enabled": true, "openReferralPoints": 8, "maxPoints": 10}',
     'Remisiones de bienestar abiertas (solo conteo; el contenido nunca sale del módulo de bienestar)'),
    ('risk.rules.causes',
     '{"enabled": true, "maxPoints": 20, "points": {"ACADEMICA": 5, "ECONOMICA": 6, "PERSONAL": 5, "SALUD": 6, "INSTITUCIONAL": 4, "OTRA": 3}}',
     'Causas de riesgo/retiro abiertas, por categoría'),
    ('risk.rules.socioeconomic-context', '{"enabled": true, "combinedPoints": 4, "maxPoints": 8}',
     'Contexto socioeconómico SOLO combinado con una señal académica o de asistencia. El estrato nunca suma puntos'),
    ('risk.ml.active-version', '""',
     'Versión del modelo ML activo (vacío = solo reglas). Debe estar APROBADO; para volver atrás se pone la versión anterior'),
    ('risk.ml.levels', '{"medium": 0.30, "high": 0.60}',
     'Probabilidad calibrada desde la que el riesgo es MEDIO y ALTO cuando hay modelo ML activo'),
    ('risk.watch-days', '30', 'Días que un estudiante queda en vigilancia tras un umbral de asistencia o una justificación rechazada'),
    ('risk.ai-can-escalate', 'false',
     'Si es true, la IA puede SUBIR el nivel (nunca bajarlo); si es false, la IA solo explica y recomienda'),
    ('risk.event-debounce-seconds', '120', 'Espera antes de reanalizar tras cambios de notas o asistencia (antirrebote)'),
    ('ai.enabled', 'true', 'Interruptor general de la IA; en false todo se resuelve con el motor de reglas'),
    ('ai.daily-token-budget', '2000000', 'Presupuesto diario de tokens (entrada + salida); al agotarse se usa el motor de reglas'),
    ('ai.free-text.enabled', 'false',
     'Enviar texto libre de observaciones a la IA (siempre pasa por el redactor de PII). Por defecto NO se envía'),
    ('ai.payload.include-sensitive-signals', 'false',
     'Incluir señales de bienestar (solo conteos) en el payload de la IA. Requiere consentimiento expreso (dato sensible)'),
    ('ai.min-level', '"MEDIO"', 'Nivel mínimo de las reglas para pedir explicación a la IA en análisis por evento o lote'),
    ('ai.agent.enabled', 'false', 'Habilita el agente de retención (borradores con herramientas de solo lectura)'),
    ('ai.support-plan.min-level', '"CRITICO"', 'Nivel de asistencia desde el que el plan de apoyo lo redacta la IA (antes: plantilla de reglas)');

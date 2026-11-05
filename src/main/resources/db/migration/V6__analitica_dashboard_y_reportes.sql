-- Fase 6: analítica (vistas materializadas), series históricas de deserción y reportes asíncronos.
--
-- Las vistas materializadas son el modelo de lectura del módulo analytics: la aplicación nunca cruza
-- tablas de otros módulos en caliente, solo lee estas vistas. Se refrescan con analytics_refresh()
-- (SECURITY DEFINER: el rol de la aplicación no es dueño de las vistas).

-- --------------------------------------------------------------- Series históricas de deserción
CREATE TABLE dropout_history (
    id           uuid        PRIMARY KEY,
    program_id   uuid        NOT NULL REFERENCES programs (id),
    period_month date        NOT NULL CHECK (EXTRACT(DAY FROM period_month) = 1),
    semester     integer     CHECK (semester BETWEEN 1 AND 14),
    enrolled     integer     NOT NULL CHECK (enrolled >= 0),
    dropouts     integer     NOT NULL CHECK (dropouts >= 0),
    label        varchar(20),
    imported_by  uuid,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_dropout_history_counts CHECK (dropouts <= enrolled)
);
CREATE UNIQUE INDEX uq_dropout_history_natural ON dropout_history (program_id, period_month, COALESCE(semester, 0));
CREATE INDEX ix_dropout_history_month ON dropout_history (period_month);

ALTER TABLE import_jobs DROP CONSTRAINT import_jobs_import_type_check;
ALTER TABLE import_jobs ADD CONSTRAINT import_jobs_import_type_check
    CHECK (import_type IN ('STUDENTS', 'TEACHERS', 'SUBJECTS', 'GROUPS', 'ENROLLMENTS', 'GRADES', 'DROPOUT_HISTORY'));

-- --------------------------------------------------------------- Modelo de lectura
-- Una fila por (estudiante, periodo). is_dropout marca solo el último periodo del estudiante, para que
-- un retiro no cuente en todos los periodos que cursó. Solo estudiantes con alguna inscripción.
CREATE MATERIALIZED VIEW analytics_student_facts AS
SELECT s.id                                     AS student_id,
       s.program_id,
       s.current_semester                       AS semester,
       st.term_id,
       EXTRACT(YEAR FROM t.start_date)::integer AS term_year,
       t.start_date                             AS term_start,
       t.end_date                               AS term_end,
       COALESCE(r.last_level, 'SIN_ANALISIS')   AS risk_level,
       s.status,
       s.status = 'RETIRADO'
           AND row_number() OVER (PARTITION BY s.id ORDER BY t.start_date DESC, st.term_id) = 1 AS is_dropout,
       CASE WHEN s.status = 'RETIRADO'
            THEN COALESCE(rc.closed_on, (s.updated_at AT TIME ZONE 'America/Bogota')::date) END AS withdrawn_on
FROM students s
JOIN (SELECT DISTINCT student_id, term_id FROM enrollments) st ON st.student_id = s.id
JOIN terms t ON t.id = st.term_id
LEFT JOIN risk_student_state r ON r.student_id = s.id
LEFT JOIN LATERAL (SELECT max((c.closed_at AT TIME ZONE 'America/Bogota')::date) AS closed_on
                   FROM retention_cases c
                   WHERE c.student_id = s.id AND c.outcome IN ('RETIRADO', 'TRANSFERIDO')) rc ON true
WHERE s.deleted_at IS NULL;

CREATE UNIQUE INDEX uq_analytics_student_facts ON analytics_student_facts (student_id, term_id);
CREATE INDEX ix_analytics_facts_term ON analytics_student_facts (term_id, program_id);
CREATE INDEX ix_analytics_facts_program ON analytics_student_facts (program_id, semester);
CREATE INDEX ix_analytics_facts_year ON analytics_student_facts (term_year, program_id);
CREATE INDEX ix_analytics_facts_dropout ON analytics_student_facts (withdrawn_on) WHERE is_dropout;

-- Faltas agregadas por materia. Parte de las inscripciones activas (no de attendance_records, que
-- tiene millones de filas): el contador ya está calculado por inscripción.
CREATE MATERIALIZED VIEW analytics_attendance_cube AS
SELECT sub.id                                   AS subject_id,
       sub.code                                 AS subject_code,
       sub.name                                 AS subject_name,
       s.program_id,
       s.current_semester                       AS semester,
       e.term_id,
       EXTRACT(YEAR FROM t.start_date)::integer AS term_year,
       t.start_date                             AS term_start,
       t.end_date                               AS term_end,
       COALESCE(r.last_level, 'SIN_ANALISIS')   AS risk_level,
       count(*)                                 AS enrollments,
       COALESCE(sum(ac.counted_absences), 0)    AS absences,
       count(*) FILTER (WHERE ac.level = 'AVISO')   AS aviso,
       count(*) FILTER (WHERE ac.level = 'CRITICO') AS critico,
       count(*) FILTER (WHERE ac.level = 'PERDIDA') AS perdida
FROM enrollments e
JOIN course_groups g ON g.id = e.group_id
JOIN subjects sub ON sub.id = g.subject_id
JOIN students s ON s.id = e.student_id
JOIN terms t ON t.id = e.term_id
LEFT JOIN absence_counters ac ON ac.enrollment_id = e.id
LEFT JOIN risk_student_state r ON r.student_id = s.id
WHERE s.deleted_at IS NULL AND e.status = 'ACTIVA'
GROUP BY sub.id, sub.code, sub.name, s.program_id, s.current_semester, e.term_id, t.start_date, t.end_date,
         COALESCE(r.last_level, 'SIN_ANALISIS');

CREATE UNIQUE INDEX uq_analytics_attendance_cube
    ON analytics_attendance_cube (subject_id, program_id, semester, term_id, risk_level);
CREATE INDEX ix_analytics_attendance_term ON analytics_attendance_cube (term_id, program_id);

-- Embudo de retención. El periodo del caso puede faltar: se usa el UUID nulo para el índice único.
CREATE MATERIALIZED VIEW analytics_retention_cube AS
SELECT c.program_id,
       s.current_semester                                                AS semester,
       COALESCE(c.term_id, '00000000-0000-0000-0000-000000000000'::uuid) AS term_id,
       EXTRACT(YEAR FROM COALESCE(t.start_date, (c.opened_at AT TIME ZONE 'America/Bogota')::date))::integer AS term_year,
       date_trunc('month', c.opened_at AT TIME ZONE 'America/Bogota')::date AS opened_month,
       COALESCE(r.last_level, 'SIN_ANALISIS')                            AS risk_level,
       c.contacted_at IS NOT NULL                                        AS contacted,
       c.stage <> 'CERRADO'                                              AS in_progress,
       COALESCE(c.outcome, '')                                           AS outcome,
       count(*)                                                          AS cases
FROM retention_cases c
JOIN students s ON s.id = c.student_id
LEFT JOIN terms t ON t.id = c.term_id
LEFT JOIN risk_student_state r ON r.student_id = c.student_id
WHERE s.deleted_at IS NULL
GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9;

CREATE UNIQUE INDEX uq_analytics_retention_cube
    ON analytics_retention_cube (program_id, semester, term_id, term_year, opened_month, risk_level, contacted,
                                 in_progress, outcome);

CREATE TABLE analytics_refresh_state (
    id           smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    dirty        boolean     NOT NULL DEFAULT false,
    dirty_since  timestamptz,
    refreshed_at timestamptz
);
INSERT INTO analytics_refresh_state (id) VALUES (1);

CREATE FUNCTION analytics_refresh() RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = ${flyway:defaultSchema} AS
$$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY analytics_student_facts;
    REFRESH MATERIALIZED VIEW CONCURRENTLY analytics_attendance_cube;
    REFRESH MATERIALIZED VIEW CONCURRENTLY analytics_retention_cube;
END;
$$;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appDbRole}') THEN
        EXECUTE format('GRANT EXECUTE ON FUNCTION analytics_refresh() TO %I', '${appDbRole}');
        EXECUTE format('GRANT SELECT ON analytics_student_facts, analytics_attendance_cube, analytics_retention_cube TO %I',
                       '${appDbRole}');
    END IF;
END;
$$;

-- --------------------------------------------------------------- Reportes asíncronos
CREATE TABLE report_jobs (
    id               uuid         PRIMARY KEY,
    requested_by     uuid         NOT NULL,
    report_type      varchar(24)  NOT NULL CHECK (report_type IN ('GENERAL', 'BY_PROGRAM', 'BY_SEMESTER', 'BY_YEAR',
                                                                  'AT_RISK_STUDENTS', 'ATTENDANCE')),
    format           varchar(8)   NOT NULL CHECK (format IN ('PDF', 'XLSX')),
    filters          text         NOT NULL,
    scope_programs   text,
    include_identity boolean      NOT NULL DEFAULT false,
    status           varchar(12)  NOT NULL CHECK (status IN ('PENDIENTE', 'PROCESANDO', 'LISTO', 'FALLIDO', 'EXPIRADO')),
    lease_until      timestamptz,
    attempts         integer      NOT NULL DEFAULT 0,
    object_key       varchar(200),
    file_name        varchar(120),
    media_type       varchar(100),
    size_bytes       bigint,
    row_count        bigint,
    failure_code     varchar(40),
    created_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    expires_at       timestamptz,
    version          bigint       NOT NULL DEFAULT 0
);
CREATE INDEX ix_report_jobs_queue ON report_jobs (created_at) WHERE status IN ('PENDIENTE', 'PROCESANDO');
CREATE INDEX ix_report_jobs_requester ON report_jobs (requested_by, id DESC);
CREATE INDEX ix_report_jobs_expiry ON report_jobs (expires_at) WHERE status = 'LISTO';

-- --------------------------------------------------------------- Parámetros en caliente
INSERT INTO app_settings (setting_key, setting_value, description) VALUES
    ('analytics.min-cell-size', '10', 'Tamaño mínimo de celda: grupos con menos estudiantes se suprimen (reidentificación)'),
    ('analytics.refresh-min-seconds', '300', 'Espera mínima entre refrescos de las vistas de analítica'),
    ('analytics.cache-ttl-seconds', '300', 'Vida máxima en caché de una respuesta de analítica'),
    ('reports.expiry-hours', '24', 'Horas que un reporte generado queda disponible para descargar'),
    ('reports.pdf-max-rows', '20000', 'Máximo de filas de un reporte PDF; los más grandes se piden en Excel');

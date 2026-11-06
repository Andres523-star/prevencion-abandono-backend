-- Un estudiante TRANSFERIDO no cuenta como deserción (decisión del equipo, 2026-11-06).
--
-- El cierre de un caso como TRANSFERIDO deja al estudiante en estado RETIRADO: students no distingue
-- por qué se fue. Lo que decide es el último cierre de salida de su caso de retención. Sin caso se
-- sigue contando como deserción: no hay nada que indique un traslado.

DROP MATERIALIZED VIEW analytics_student_facts;

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
           AND COALESCE(rc.outcome, 'RETIRADO') <> 'TRANSFERIDO'
           AND row_number() OVER (PARTITION BY s.id ORDER BY t.start_date DESC, st.term_id) = 1 AS is_dropout,
       CASE WHEN s.status = 'RETIRADO'
            THEN COALESCE(rc.closed_on, (s.updated_at AT TIME ZONE 'America/Bogota')::date) END AS withdrawn_on
FROM students s
JOIN (SELECT DISTINCT student_id, term_id FROM enrollments) st ON st.student_id = s.id
JOIN terms t ON t.id = st.term_id
LEFT JOIN risk_student_state r ON r.student_id = s.id
LEFT JOIN LATERAL (SELECT c.outcome, (c.closed_at AT TIME ZONE 'America/Bogota')::date AS closed_on
                   FROM retention_cases c
                   WHERE c.student_id = s.id AND c.outcome IN ('RETIRADO', 'TRANSFERIDO')
                   ORDER BY c.closed_at DESC
                   LIMIT 1) rc ON true
WHERE s.deleted_at IS NULL;

CREATE UNIQUE INDEX uq_analytics_student_facts ON analytics_student_facts (student_id, term_id);
CREATE INDEX ix_analytics_facts_term ON analytics_student_facts (term_id, program_id);
CREATE INDEX ix_analytics_facts_program ON analytics_student_facts (program_id, semester);
CREATE INDEX ix_analytics_facts_year ON analytics_student_facts (term_year, program_id);
CREATE INDEX ix_analytics_facts_dropout ON analytics_student_facts (withdrawn_on) WHERE is_dropout;

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appDbRole}') THEN
        EXECUTE format('GRANT SELECT ON analytics_student_facts TO %I', '${appDbRole}');
    END IF;
END;
$$;

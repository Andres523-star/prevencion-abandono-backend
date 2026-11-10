-- Hallazgo de la prueba de pico de asistencia (AttendancePeakIT): la primera toma de lista de un periodo nuevo
-- creaba la partición dentro de la transacción de la petición; el bloqueo consultivo duraba hasta el final de
-- esa transacción y las demás tomas de lista concurrentes esperaban con su conexión tomada (pool agotado).
-- Además, una transacción podía no ver la partición recién creada por otra y fallar con "la relación ya existe".
--
-- Ahora la aplicación la crea antes de abrir la transacción (bloqueo de una sola sentencia) y la función
-- tolera la carrera entre instancias.

CREATE OR REPLACE FUNCTION attendance_ensure_partition(p_term uuid) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = ${flyway:defaultSchema} AS
$$
DECLARE
    v_name text := 'attendance_records_' || replace(p_term::text, '-', '_');
BEGIN
    IF to_regclass(v_name) IS NULL THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(v_name, 0));
        BEGIN
            EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF attendance_records FOR VALUES IN (%L)', v_name, p_term);
        EXCEPTION WHEN duplicate_table OR unique_violation THEN
            -- Otra instancia la creó al mismo tiempo: ya existe, que es lo que se quería.
            NULL;
        END;
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appDbRole}') THEN
            EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO %I', v_name, '${appDbRole}');
        END IF;
    END IF;
    RETURN v_name;
END;
$$;

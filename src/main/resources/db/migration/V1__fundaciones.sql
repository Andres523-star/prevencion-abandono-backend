-- =====================================================================================
-- V1: Fundaciones y seguridad base (Fase 1)
-- Tablas: roles, users, user_roles, user_program_scopes, local_credentials, refresh_tokens,
--         app_settings, audit_log (particionada por mes, append-only), audit_chain_head,
--         event_publication (outbox de Spring Modulith).
-- Inmutable una vez aplicada: cualquier cambio va en una migración nueva.
-- Placeholders: ${appDbRole} = rol de BD con el que corre la aplicación.
-- Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
-- =====================================================================================

-- ---------------------------------------------------------------- Roles
CREATE TABLE roles (
    code        varchar(32)  PRIMARY KEY,
    description varchar(200) NOT NULL
);

INSERT INTO roles (code, description) VALUES
    ('ADMIN',       'Administrador de la plataforma'),
    ('CONSEJERO',   'Consejero académico'),
    ('PSICOLOGO',   'Psicólogo de bienestar universitario'),
    ('COORDINADOR', 'Coordinador de programa'),
    ('DOCENTE',     'Docente o tutor'),
    ('ESTUDIANTE',  'Estudiante (portal propio)');

-- ---------------------------------------------------------------- Usuarios
-- Correo y nombre cifrados (AES-256-GCM, cifrado envolvente). email_blind_index = HMAC para buscar.
CREATE TABLE users (
    id                      uuid         PRIMARY KEY,
    auth_type               varchar(16)  NOT NULL CHECK (auth_type IN ('SUPABASE', 'LOCAL')),
    status                  varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    external_issuer         varchar(255),
    external_subject        varchar(255),
    email_ciphertext        text,
    email_blind_index       varchar(64),
    display_name_ciphertext text,
    last_login_at           timestamptz,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    deleted_at              timestamptz,
    version                 bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_external_identity UNIQUE (external_issuer, external_subject),
    CONSTRAINT uq_users_email_blind_index UNIQUE (email_blind_index),
    CONSTRAINT ck_users_external_has_email CHECK (auth_type <> 'SUPABASE' OR email_blind_index IS NOT NULL)
);

CREATE TABLE user_roles (
    user_id    uuid        NOT NULL REFERENCES users (id),
    role_code  varchar(32) NOT NULL REFERENCES roles (code),
    granted_at timestamptz NOT NULL,
    granted_by uuid,
    PRIMARY KEY (user_id, role_code)
);
CREATE INDEX ix_user_roles_role ON user_roles (role_code);

-- Alcance ABAC por programa (coordinador, consejero). program_id referenciará a programs en Fase 2.
CREATE TABLE user_program_scopes (
    user_id    uuid NOT NULL REFERENCES users (id),
    program_id uuid NOT NULL,
    PRIMARY KEY (user_id, program_id)
);
CREATE INDEX ix_user_program_scopes_program ON user_program_scopes (program_id);

-- ---------------------------------------------------------------- Autenticación
-- Cuentas break-glass: argon2id + TOTP (secreto cifrado) + bloqueo progresivo.
CREATE TABLE local_credentials (
    user_id                uuid         PRIMARY KEY REFERENCES users (id),
    username               varchar(64)  NOT NULL UNIQUE,
    password_hash          varchar(255) NOT NULL,
    totp_secret_ciphertext text         NOT NULL,
    failed_attempts        integer      NOT NULL DEFAULT 0,
    locked_until           timestamptz,
    last_totp_step         bigint       NOT NULL DEFAULT 0,
    updated_at             timestamptz  NOT NULL,
    version                bigint       NOT NULL DEFAULT 0
);

-- Refresh tokens rotativos: solo el hash SHA-256; family_id = id de sesión.
CREATE TABLE refresh_tokens (
    id                uuid        PRIMARY KEY,
    family_id         uuid        NOT NULL,
    parent_id         uuid,
    user_id           uuid        NOT NULL REFERENCES users (id),
    token_hash        varchar(64)    NOT NULL UNIQUE,
    auth_method       varchar(16) NOT NULL CHECK (auth_method IN ('SUPABASE', 'LOCAL')),
    mfa               boolean     NOT NULL,
    issued_at         timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,
    family_expires_at timestamptz NOT NULL,
    used_at           timestamptz,
    revoked_at        timestamptz,
    revoked_reason    varchar(32)
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family_expires ON refresh_tokens (family_expires_at);

-- ---------------------------------------------------------------- Configuración en caliente
CREATE TABLE app_settings (
    setting_key   varchar(128) PRIMARY KEY CHECK (setting_key ~ '^[a-z0-9]+([.-][a-z0-9]+)*$'),
    setting_value jsonb        NOT NULL,
    description   varchar(500) NOT NULL,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    uuid,
    version       bigint       NOT NULL DEFAULT 0
);

INSERT INTO app_settings (setting_key, setting_value, description) VALUES
    ('auth.lockout.max-attempts',      '3',     'Intentos fallidos de login break-glass antes del primer bloqueo'),
    ('auth.lockout.base-lock-seconds', '60',    'Duración del primer bloqueo (se duplica con cada fallo adicional)'),
    ('auth.lockout.max-lock-seconds',  '86400', 'Duración máxima del bloqueo progresivo');

-- ---------------------------------------------------------------- Auditoría (append-only)
-- seq es continuo (sin huecos) y lo asigna la app bajo bloqueo de audit_chain_head;
-- hash = SHA-256(prev_hash || contenido canónico). Particionada por mes en occurred_at.
CREATE TABLE audit_log (
    id            uuid         NOT NULL,
    seq           bigint       NOT NULL,
    occurred_at   timestamptz  NOT NULL,
    actor_user_id uuid,
    actor_roles   varchar(200),
    action        varchar(100) NOT NULL,
    entity_type   varchar(100),
    entity_id     varchar(100),
    outcome       varchar(16)  NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    ip_address    varchar(45),
    request_id    varchar(64),
    reason        varchar(500),
    details       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    prev_hash     varchar(64)     NOT NULL,
    hash          varchar(64)     NOT NULL,
    PRIMARY KEY (seq, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_audit_log_actor ON audit_log (actor_user_id, occurred_at);
CREATE INDEX ix_audit_log_entity ON audit_log (entity_type, entity_id, occurred_at);

-- Fila única con la cabeza de la cadena: su bloqueo serializa las inserciones.
CREATE TABLE audit_chain_head (
    id        smallint PRIMARY KEY CHECK (id = 1),
    last_seq  bigint   NOT NULL,
    last_hash varchar(64) NOT NULL
);
INSERT INTO audit_chain_head (id, last_seq, last_hash) VALUES (1, 0, repeat('0', 64));

-- Rechaza cualquier modificación. Aplica incluso al dueño de la tabla.
CREATE FUNCTION audit_log_reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_log es append-only: % no está permitido', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

-- Los triggers FOR EACH ROW del padre se clonan en cada partición (PostgreSQL 13+).
CREATE TRIGGER trg_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_reject_modification();

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_reject_modification();

-- Crea la partición de un mes (idempotente). SECURITY DEFINER: la app la invoca sin tener
-- permiso de CREATE. Los triggers de TRUNCATE no se heredan, por eso se añaden a cada partición.
CREATE FUNCTION audit_create_month_partition(p_month date) RETURNS text
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = ${flyway:defaultSchema} AS
$$
DECLARE
    v_start date := date_trunc('month', p_month)::date;
    v_end   date := (date_trunc('month', p_month) + interval '1 month')::date;
    v_name  text := format('audit_log_%s', to_char(v_start, 'YYYY_MM'));
BEGIN
    IF to_regclass(v_name) IS NULL THEN
        EXECUTE format('CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
                       v_name, v_start, v_end);
        EXECUTE format('CREATE TRIGGER trg_no_truncate BEFORE TRUNCATE ON %I '
                       'FOR EACH STATEMENT EXECUTE FUNCTION audit_log_reject_modification()', v_name);
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${appDbRole}') THEN
            EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM %I', v_name, '${appDbRole}');
            EXECUTE format('GRANT SELECT, INSERT ON %I TO %I', v_name, '${appDbRole}');
        END IF;
    END IF;
    RETURN v_name;
END;
$$;

CREATE FUNCTION audit_ensure_partitions(p_months_ahead integer) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = ${flyway:defaultSchema} AS
$$
BEGIN
    FOR i IN 0..greatest(p_months_ahead, 0) LOOP
        PERFORM audit_create_month_partition((date_trunc('month', now()) + make_interval(months => i))::date);
    END LOOP;
END;
$$;

-- Partición por defecto: recibe filas fuera de rango (p. ej. relojes desfasados) en lugar de fallar.
CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;
CREATE TRIGGER trg_no_truncate BEFORE TRUNCATE ON audit_log_default
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_reject_modification();

SELECT audit_ensure_partitions(3);

-- ---------------------------------------------------------------- Outbox de eventos (Spring Modulith)
CREATE TABLE event_publication (
    id               uuid        PRIMARY KEY,
    listener_id      text        NOT NULL,
    event_type       text        NOT NULL,
    serialized_event text        NOT NULL,
    publication_date timestamptz NOT NULL,
    completion_date  timestamptz
);
CREATE INDEX ix_event_publication_incomplete ON event_publication (publication_date) WHERE completion_date IS NULL;

CREATE TABLE event_publication_archive (
    id               uuid        PRIMARY KEY,
    listener_id      text        NOT NULL,
    event_type       text        NOT NULL,
    serialized_event text        NOT NULL,
    publication_date timestamptz NOT NULL,
    completion_date  timestamptz
);

-- ---------------------------------------------------------------- Permisos del rol de la aplicación
-- Solo si el rol existe (en Render hay un único usuario de BD; ahí protege el trigger).
DO
$$
DECLARE
    v_role   text := '${appDbRole}';
    v_schema text := '${flyway:defaultSchema}';
    v_part   record;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_role) AND v_role <> current_user THEN
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', v_schema, v_role);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', v_schema, v_role);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
                       v_schema, v_role);
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM %I', v_role);
        FOR v_part IN
            SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
            WHERE i.inhparent = 'audit_log'::regclass
        LOOP
            EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM %I', v_part.relname, v_role);
        END LOOP;
        -- Catálogos de solo lectura para la app.
        EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON roles FROM %I', v_role);
        EXECUTE format('REVOKE DELETE ON app_settings FROM %I', v_role);
        IF to_regclass('flyway_schema_history') IS NOT NULL THEN
            EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON flyway_schema_history FROM %I', v_role);
        END IF;
        EXECUTE format('GRANT EXECUTE ON FUNCTION audit_ensure_partitions(integer) TO %I', v_role);
    END IF;
END;
$$;

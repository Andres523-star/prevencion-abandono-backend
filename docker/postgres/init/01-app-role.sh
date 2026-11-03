#!/bin/sh
# Crea el rol de la aplicación con privilegios mínimos. Flyway migra con POSTGRES_USER (dueño)
# y la migración V1 otorga a este rol solo lo necesario (sin UPDATE/DELETE en audit_log).
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'app_user', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user')\gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'app_user')\gexec
SQL

#!/usr/bin/env bash
# Restaura un backup de backup.sh en una base NUEVA (nunca sobre la de producción).
#
# Uso:
#   ADMIN_DATABASE_URL=postgresql://usuario:clave@host:5432/postgres BACKUP_PASSPHRASE='...' \
#     ./restore.sh retencion-20261109T020000Z.dump.gpg retencion_restaurada
#
# Requisitos: el rol de la aplicación (DB_APP_ROLE, p. ej. retencion_app) debe existir en el servidor de
# destino, porque los permisos del dump lo nombran. Los roles no viajan en pg_dump.
# Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
set -euo pipefail

: "${ADMIN_DATABASE_URL:?Falta ADMIN_DATABASE_URL}"
: "${BACKUP_PASSPHRASE:?Falta BACKUP_PASSPHRASE}"
file="${1:?Indica el archivo .dump.gpg}"
target="${2:?Indica el nombre de la base de destino}"
[[ "$target" =~ ^[a-z_][a-z0-9_]*$ ]] || { echo "Nombre de base inválido: $target" >&2; exit 2; }

(cd "$(dirname "$file")" && sha256sum --check --quiet "$(basename "$file").sha256")

psql --quiet --no-psqlrc --set=ON_ERROR_STOP=1 --dbname="$ADMIN_DATABASE_URL" -c "CREATE DATABASE $target"
target_url="${ADMIN_DATABASE_URL%/*}/$target"

gpg --batch --quiet --pinentry-mode loopback --decrypt --passphrase-fd 3 "$file" 3< <(printf '%s' "$BACKUP_PASSPHRASE") \
  | pg_restore --no-owner --exit-on-error --dbname="$target_url"

echo "Restaurado en $target"

#!/usr/bin/env bash
# Backup lógico cifrado de PostgreSQL. Complementa los backups gestionados de Render (ver docs/backups.md).
#
# Uso:
#   DATABASE_URL=postgresql://usuario:clave@host:5432/retencion BACKUP_PASSPHRASE='...' ./backup.sh [salida.dump.gpg]
#
# - pg_dump en formato custom (comprimido, restauración selectiva) con privilegios incluidos.
# - Cifrado simétrico gpg AES-256 con verificación de integridad (MDC). La frase va por un descriptor,
#   nunca por la línea de comandos (no aparece en `ps`).
# - Suma SHA-256 al lado para detectar archivos dañados antes de restaurar.
# Asistido por IA (Claude, Anthropic). Ver BITACORA_IA.md.
set -euo pipefail

: "${DATABASE_URL:?Falta DATABASE_URL}"
: "${BACKUP_PASSPHRASE:?Falta BACKUP_PASSPHRASE}"
out="${1:-retencion-$(date -u +%Y%m%dT%H%M%SZ).dump.gpg}"

pg_dump --format=custom --compress=6 --dbname="$DATABASE_URL" \
  | gpg --batch --yes --quiet --pinentry-mode loopback --symmetric --cipher-algo AES256 \
        --passphrase-fd 3 --output "$out" 3< <(printf '%s' "$BACKUP_PASSPHRASE")

(cd "$(dirname "$out")" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256")
echo "Backup listo: $out"

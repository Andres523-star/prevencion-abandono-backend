# Backups y restauración

## Política

| Capa | Qué | Frecuencia | Retención | Cifrado |
|---|---|---|---|---|
| Render PostgreSQL | Backups gestionados (y recuperación a un punto en el tiempo en los planes que la incluyen) | Diaria / continua | Según el plan | En reposo, por Render |
| Backup lógico propio | `ops/backup/backup.sh`: `pg_dump` formato custom + gpg AES-256 + SHA-256 | Semanal y antes de cada migración mayor | 12 semanas | gpg simétrico; la frase vive en el gestor de secretos, nunca junto al archivo |
| Archivos (evidencias, adjuntos) | Versionado o réplica del bucket S3 | Continua | 90 días | Del proveedor |

El backup lógico protege contra lo que el gestionado no cubre: borrar la cuenta o el servicio de Render, o tener
que mover la base a otro proveedor. Se guarda en un bucket de **otra** cuenta y región.

**Objetivos iniciales:** RPO 24 h (gestionado) / 7 días (lógico); RTO 4 h. Ajustar con el contrato de Render.

## Restaurar

```bash
ADMIN_DATABASE_URL=postgresql://usuario:clave@host:5432/postgres BACKUP_PASSPHRASE='...' \
  ops/backup/restore.sh retencion-20261109T020000Z.dump.gpg retencion_restaurada
```

1. Verifica la suma SHA-256, crea una base **nueva** y restaura (nunca sobre la base en uso).
2. El rol de la aplicación (`DB_APP_ROLE`) debe existir en el servidor de destino: los roles no viajan en el dump.
3. Comprueba `GET /api/v1/audit/verify` sobre la base restaurada y las cifras de control (estudiantes, auditoría).
4. Apunta el servicio a la base nueva cambiando `DB_*` en Render; las claves `APP_CRYPTO_*` deben ser las mismas o
   los datos cifrados no se podrán leer.

## Prueba de restauración

`BackupRestoreIT` corre los scripts reales contra la base de pruebas: backup cifrado, el archivo no contiene texto
en claro, restauración en una base nueva y verificación de **las mismas filas en cada tabla**, el mismo último hash
de la cadena de auditoría, los mismos triggers append-only (la base restaurada sigue rechazando `TRUNCATE` de la
auditoría) y las tres vistas de analítica. Resultado del 2026-11-10: verde (PostgreSQL 16, gpg, bash).

Repetir el procedimiento manual contra un backup real de Render antes del piloto y una vez por trimestre.

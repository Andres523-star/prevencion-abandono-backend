# Despliegue y salida a producción

La indicación original de Oracle Cloud Bogotá queda reemplazada por **Render** (decisión del equipo, 2026-11-03).
La configuración base del Blueprint está en [render-despliegue.md](render-despliegue.md).

## Ambientes

| Ambiente | Cómo | Datos |
|---|---|---|
| Local | `docker compose up` + perfil `dev` (datos sintéticos opcionales) | Sintéticos |
| Staging | Proyecto de Render aparte con el mismo `render.yaml` y sus propios secretos | Sintéticos o anonimizados, nunca reales |
| Producción | Proyecto de Render con revisores obligatorios en GitHub (`environment: production`) | Reales |

Cada ambiente tiene sus propios secretos (`APP_JWT_SECRET`, `APP_CRYPTO_*`, Supabase, IA) en el panel de Render o en
un *Environment Group*; nunca en el repositorio. Las claves criptográficas de producción se generan una vez y no se
regeneran (se perderían los datos cifrados).

## Pipeline

1. **CI** (`.github/workflows/ci.yml`): `./mvnw verify` (unitarias, integración, arquitectura, cobertura,
   recorrido de seguridad), SBOM CycloneDX, Trivy (dependencias, SBOM e imagen), gitleaks.
2. **CD** (`.github/workflows/deploy.yml`): si CI pasa en `main`, despliega en staging por deploy hook. Producción se
   despliega a mano (`workflow_dispatch`) con aprobación del entorno.
3. **Render** construye la imagen y corre el `preDeployCommand`:
   `java -cp "/app/BOOT-INF/classes:/app/BOOT-INF/lib/*" co.edu.retencion.common.ops.MigrateMain`. Si la migración
   falla, el despliegue se cancela y sigue la versión anterior.
4. La aplicación **no migra** al arrancar en prod (`APP_FLYWAY_MIGRATE_ON_STARTUP=false`): solo valida que el esquema
   coincida exactamente; si no, la instancia no arranca.

## Migraciones

Inmutables una vez aplicadas (Flyway valida los checksums). Cambios compatibles hacia atrás: primero se agrega
(columna, tabla), luego se usa, y se elimina lo viejo en un despliegue posterior, así un rollback de la aplicación no
choca con el esquema. Backup lógico antes de una migración mayor ([backups.md](backups.md)).

## Rollback

Render → *Rollback* al despliegue anterior. Si una migración no es compatible hacia atrás, restaurar el backup.

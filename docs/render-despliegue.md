# Despliegue en Render

## Qué crea el Blueprint (`render.yaml`)

| Recurso | Plan sugerido | Notas |
|---|---|---|
| PostgreSQL 16 `retencion-db` | `basic-1gb` (o superior) | Sin acceso público (`ipAllowList: []`). El plan gratuito caduca a los 30 días: no usarlo. |
| Key Value `retencion-redis` | `starter` | Rate limit y anti-replay compartidos entre instancias. `noeviction`. |
| Web `retencion-backend` | `standard` (2 GB) | Docker, 1–3 instancias con autoescalado, health check en `/actuator/health/readiness`. |

La región configurada es `virginia` (la más cercana a Latinoamérica); se puede cambiar en `render.yaml`.
La identidad la gestiona Supabase Auth (servicio externo), ver [supabase-auth.md](supabase-auth.md).

## Pasos

1. Sube el repositorio a GitHub.
2. En Render: **New → Blueprint** → selecciona el repo → Render lee `render.yaml`.
3. Completa las variables marcadas `sync: false`: `SUPABASE_URL`, `SUPABASE_ANON_KEY`,
   `APP_CORS_ALLOWED_ORIGINS` (URL del frontend), `APP_BOOTSTRAP_ADMIN_EMAILS` (tu correo, para ser
   el primer ADMIN) y, si quieres restringir, `APP_ALLOWED_EMAIL_DOMAINS`.
4. Render genera `APP_JWT_SECRET`, `APP_CRYPTO_MASTER_KEY` y `APP_CRYPTO_BLIND_INDEX_KEY`.
   **Cópialas a un gestor de secretos**: si se pierden o regeneran, los datos cifrados quedan ilegibles.
5. Deploy. Flyway aplica las migraciones al arrancar (con bloqueo, seguro con varias instancias).
6. Verifica: `GET https://<servicio>.onrender.com/actuator/health` → `{"status":"UP"}`.
7. En Supabase, agrega la URL del frontend a *Redirect URLs* (Authentication → URL Configuration).

## Escalabilidad ("hartos usuarios")

- Backend sin estado: la sesión es JWT y el estado compartido vive en Redis → se escala horizontalmente.
- Pool de conexiones por instancia `DB_POOL_MAX=15`; con 3 instancias = 45 conexiones, dentro del
  límite de `basic-1gb`. Si se suben instancias, subir el plan de la base de datos.
- Hilos virtuales de Java 21 activados (`spring.threads.virtual.enabled`).
- IDs UUID v7, auditoría particionada por mes y paginación keyset, pensados para millones de filas.

## Endurecimiento opcional

- Render da un único usuario de base de datos. La migración otorga privilegios mínimos solo si existe
  un rol de aplicación distinto (`DB_APP_ROLE`); en Render la inmutabilidad de `audit_log` la
  garantiza el **trigger** (aplica incluso al dueño de la tabla).
- IP real del cliente: el perfil `prod` usa `forward-headers-strategy: native` (Tomcat RemoteIpValve,
  confía solo en proxies de red privada). Si los logs muestran IPs del balanceador, ajustar
  `APP_INTERNAL_PROXIES_REGEX` o `APP_CLIENT_IP_HEADER`.
- Ley 1581 de 2012 (Colombia) sigue aplicando al tratamiento de datos personales aunque el
  alojamiento esté fuera del país: mantener política de privacidad, consentimiento y registro de
  finalidades (Fase 7).

# Runbook de incidentes

Primero contener, luego entender. Toda acción con datos personales se registra en la auditoría; no copies datos a
canales de chat ni a tickets.

## Backend caído

1. Render → servicio → *Events* y *Logs*: ¿falló el `preDeployCommand` (migración) o la sonda de *readiness*?
2. Si fue un despliegue: *Rollback* al despliegue anterior en Render (la migración ya aplicada es compatible hacia
   atrás por diseño; si no, restaurar según [backups.md](backups.md)).
3. Si la sonda falla por base o Redis: revisar el estado de esos servicios en Render.

## Errores 5xx

Buscar en los logs por `requestId` y el código `internal_error`. Las causas típicas tienen métrica propia: pool
agotado (`hikaricp_connections_pending`), IA caída (el motor sigue con reglas), almacenamiento o antivirus caídos.

## Latencia

Revisar el panel de pool y de colas. En un pico de asistencia la toma de lista pasa por un bulkhead
(`APP_ATTENDANCE_MAX_CONCURRENT`): esperar es normal; un 503 con `Retry-After` indica saturación sostenida → ver
[dimensionamiento.md](dimensionamiento.md).

## Pool

Peticiones esperando conexión: revisar consultas lentas (`pg_stat_activity`), subir `DB_POOL_MAX` sin pasar el
límite de conexiones del plan de PostgreSQL, o mover la analítica a la réplica (`APP_ANALYTICS_READ_URL`).

## Eventos

`retencion_events_pending` alto y sin bajar: un listener está fallando. Los logs de Modulith dicen cuál ("Invocation
of listener ... failed"). Corregido el error, las publicaciones pendientes se reintentan al reiniciar.

## Correo

Avisos fallidos: credenciales del proveedor, dominio sin SPF/DKIM o cuota agotada. Los avisos no se pierden: quedan
en la bandeja con reintentos.

## Solicitudes del titular

Una solicitud vencida es un incumplimiento legal: atenderla de inmediato (`GET /api/v1/privacy/data-requests`) y
dejar constancia del motivo del retraso.

## Brecha de datos personales

1. **Contener:** revocar accesos comprometidos (desactivar usuarios, rotar `APP_JWT_SECRET` si se filtró: cierra todas
   las sesiones), cerrar el vector.
2. **Preservar evidencia:** no borrar logs; exportar la auditoría del periodo (`audit_log` es append-only).
3. **Evaluar:** qué datos, de cuántos titulares, si incluye datos sensibles (bienestar, salud), si la identidad
   cifrada y sus claves quedaron expuestas juntas.
4. **Notificar:** a la dirección y al área jurídica de inmediato; a la **SIC** por el RNBD dentro de los 15 días
   hábiles siguientes a la detección; a los titulares afectados cuando haya riesgo para ellos, con lenguaje claro.
5. **Rotar claves** si aplica (`APP_CRYPTO_MASTER_KEY` con `APP_CRYPTO_PREVIOUS_KEYS` para releer lo anterior).
6. **Cerrar:** informe con causa raíz, acciones y fecha; revisar este runbook.

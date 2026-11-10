# Dimensionamiento y escalabilidad

## Punto de partida (Render)

| Componente | Plan inicial | Notas |
|---|---|---|
| Backend | `standard` (2 GB), **1 instancia** | Ver "Varias instancias" |
| PostgreSQL 16 | `basic-1gb` | Subir a un plan con más conexiones y réplica de lectura antes de 50.000 estudiantes activos |
| Key Value (Redis) | `starter`, `noeviction` | Rate limit, tickets de login, caché de analítica |
| ClamAV | servicio privado `standard` | ~2 GB de RAM |

## Conexiones y concurrencia

Con hilos virtuales el servidor acepta muchas peticiones a la vez; lo que limita es el **pool de conexiones**:

- `DB_POOL_MAX` (15 en Render): conexiones por instancia. La suma de todas las instancias debe quedar por debajo del
  `max_connections` del plan de PostgreSQL, con margen para migraciones y mantenimiento.
- `APP_ASYNC_LISTENER_THREADS` (6): hilos para los listeners de eventos. Acotados a propósito: sin límite, un pico
  de asistencia generaba cientos de listeners que agotaban el pool (hallazgo de `AttendancePeakIT`).
- `APP_ATTENDANCE_MAX_CONCURRENT` (10) y `APP_ATTENDANCE_MAX_WAIT` (60 s): bulkhead de la toma de lista. Las demás
  esperan turno **sin** conexión tomada; pasado el tiempo responden 503 con `Retry-After`.
- Regla: `listener-threads + attendance-max-concurrent + lecturas concurrentes ≈ DB_POOL_MAX`.

## Particionado

`attendance_records` está particionada por periodo (`LIST (term_id)`); las consultas de la app usan la poda de
particiones (verificado con `EXPLAIN` en `AttendanceVolumeIT` y `AttendancePeakIT`). La partición del periodo se crea
fuera de la transacción de la primera toma de lista (V9). Los periodos cerrados se pueden separar (`DETACH`) y
archivar sin afectar al activo.

## Réplica de lectura para analítica

`APP_ANALYTICS_READ_URL` (+ usuario, clave y `APP_ANALYTICS_READ_POOL`) envía los gráficos, el tablero y los reportes a
una réplica, con su propio pool. El refresco de las vistas materializadas sigue en la principal y llega a la réplica
por replicación física. La analítica tolera el atraso (ya tiene hasta 5 minutos por diseño).

## Varias instancias

Hoy `render.yaml` fija **una** instancia porque el tiempo real usa el broker STOMP simple en memoria: con dos
instancias, un mensaje del chat podría no llegar a un usuario conectado a la otra. Para escalar horizontalmente:

1. RabbitMQ con el plugin `rabbitmq_stomp` (p. ej. un servicio gestionado externo) y `APP_REALTIME_BROKER=relay`.
2. `APP_STATE_BACKEND=redis` (ya en prod) para rate limit y tickets compartidos.
3. Subir `numInstances` o configurar autoescalado. Los jobs programados ya son seguros con varias instancias
   (`FOR UPDATE SKIP LOCKED` y bloqueos consultivos).

## Capacidad medida

Ver [rendimiento.md](rendimiento.md): analítica con p95 de 17 ms a 100 peticiones/s y pico de asistencia de 612 tomas de lista en 5 minutos con p95 de 110 ms (en un portátil).

# Asistencia, justificaciones y correo de avisos (Fase 3)

## Asistencia

- El docente del grupo toma asistencia por sesión (`PUT /api/v1/sessions/{id}/attendance`,
  uno o varios estudiantes): `PRESENTE`, `FALTA` o `TARDANZA`.
- **Una sesión ausente es una falta**, dure lo que dure (una clase de 4 horas = 1 falta).
- Ventana de edición: `attendance.edit-window-hours` (48 por defecto, en settings), contada
  desde el fin de la sesión en hora de Bogotá. Pasada la ventana solo COORDINADOR o ADMIN
  corrigen, y deben enviar el motivo en `X-Access-Reason`; queda en la auditoría.
- No se toma asistencia de sesiones futuras ni canceladas.
- La tabla `attendance_records` está particionada por periodo (`LIST (term_id)`); la partición se
  crea sola con `attendance_ensure_partition` antes de la primera escritura del periodo.

### Contador de faltas

`absence_counters` (una fila por inscripción) se recalcula en la misma transacción de cada cambio:

| Campo | Significado |
|---|---|
| `unjustified` | Sin justificar, rechazadas o vencidas |
| `pending` | Justificación enviada, pendiente del docente (**sigue contando**) |
| `justified` | Aprobadas por el docente (no cuentan) |
| `tardies` | Tardanzas (cuentan solo si la política define `tardiesPerAbsence`) |
| `countedAbsences` | `unjustified + pending + tardies / tardiesPerAbsence` |
| `level` | `NINGUNO` → `AVISO` → `CRITICO` → `PERDIDA` según la política efectiva |

Cuando el nivel **sube** se publica `AttendanceThresholdReached` (AVISO o CRÍTICO) o
`SubjectLostByAbsences` (PERDIDA). Los consumirán el motor de riesgo (Fase 4) y las alertas
(Fase 5). `POST /groups/{id}/absence-counters/recalculate` rehace los contadores tras cambiar una
política.

## Justificaciones

1. El estudiante justifica una de sus faltas dentro del plazo (días hábiles de la política, sin
   festivos): `POST /api/v1/justifications` con categoría (`SALUD`, `FAMILIAR`, `LABORAL`,
   `TRANSPORTE`, `OTRO`) y descripción (cifrada en columna).
2. Adjunta evidencias: `POST /api/v1/justifications/{id}/evidences` (multipart `file`).
   - Solo PDF, JPG o PNG detectados por *magic bytes*; la extensión debe coincidir.
   - Tamaño máximo `APP_EVIDENCE_MAX_SIZE` (5 MB) y 5 evidencias por justificación.
   - Antivirus **antes** de guardar: un archivo infectado se rechaza con `422 file_infected` y
     nunca llega al almacenamiento. Si el antivirus no responde, la evidencia queda en `ERROR`,
     no se puede descargar ni decidir, y un job la vuelve a escanear cada 5 minutos.
   - Nombre aleatorio en el almacenamiento (`evidencias/<uuid v7>-pdf`); descarga solo con URL
     prefirmada de 5 minutos (`GET /evidences/{id}/download-url`), auditada.
3. **Solo el docente actual del grupo** decide (`POST /justifications/{id}/decision`). Otro
   docente, coordinación, consejería y administración reciben 403. Rechazar exige comentario.
   - Aprobada: la falta se descuenta. Rechazada: sigue contando como injustificada.
4. Si el grupo cambia de docente, las pendientes pasan al nuevo (evento `GroupTeacherChanged`).
5. Vencimiento: a las 00:05 (Bogotá) las faltas sin justificar con plazo cumplido pasan a
   `VENCIDA` (también `POST /justifications/expire`, ADMIN).

## Correo de avisos al estudiante

Es el **único** uso de correo de la plataforma. El personal se entera por la campanita (Fase 5).

- `AbsenceRecorded` → fila en `email_outbox` con `not_before` = fin de la ventana de edición
  (`mail.absence-mode = AFTER_EDIT_WINDOW`) o la siguiente hora de resumen
  (`DAILY_DIGEST`, hora `mail.digest-hour`). Si el docente corrige la falta antes, el aviso se
  cancela (`AbsenceRetracted`).
- Al enviar se revalida la falta: si ya no es falta o el docente aprobó la justificación, no sale.
  La plantilla se elige con el contador del momento: `primera-falta`, `falta-registrada`,
  `aviso`, `critico`, `perdida`; además `justificacion-aprobada`, `justificacion-rechazada` y
  `resumen-diario` (`src/main/resources/templates/mail/`).
- Contenido: materia, fecha, faltas que cuentan, límite, plazo para justificar y enlace al portal.
  **Nunca** riesgo, salud, información psicológica, categoría ni texto de la justificación,
  ni el comentario del docente (puede mencionar salud). Prueba: `AbsenceMailIT`.
- La dirección no se guarda en el outbox: se resuelve al enviar desde la identidad cifrada.
- Despacho cada 30 s con `FOR UPDATE SKIP LOCKED` (varias instancias sin duplicar), reintentos
  con backoff exponencial (1 min → 6 h, 8 intentos) y entrega al menos una vez.
- Rebotes: `POST /api/v1/mail/bounces` con `{"providerMessageId", "reason"}` y la cabecera
  `X-Mail-Signature` = HMAC-SHA256 hex del cuerpo con `APP_MAIL_WEBHOOK_SECRET`. El envío queda
  `REBOTADO` y la dirección se suprime (por su blind index) hasta que se rectifique el correo.
- Registro de entrega: `GET /api/v1/mail/outbox` (ADMIN, sin direcciones).

### Proveedores (`APP_MAIL_PROVIDER`)

| Valor | Uso | Variables |
|---|---|---|
| `logging` | Desarrollo: no envía, registra solo el asunto | — |
| `resend` | Render u otros sin Microsoft 365 | `APP_MAIL_RESEND_API_KEY`, `APP_MAIL_FROM` |
| `graph` | Universidad con Microsoft 365: `sendMail` desde un buzón dedicado | `APP_MAIL_GRAPH_TENANT_ID`, `APP_MAIL_GRAPH_CLIENT_ID`, `APP_MAIL_GRAPH_CLIENT_SECRET`, `APP_MAIL_GRAPH_MAILBOX` |
| `memory` | Pruebas automáticas | — |

Para Graph: registrar una aplicación en Entra ID con permiso de aplicación `Mail.Send` y
restringirlo al buzón con una *application access policy*
(`New-ApplicationAccessPolicy -AccessRight RestrictAccess`).

## Eventos y RabbitMQ

`AbsenceRecorded`, `AttendanceThresholdReached` y `SubjectLostByAbsences` llevan `@Externalized`:
con `APP_RABBIT_ENABLED=true` Spring Modulith los publica, después de confirmar la transacción,
en el exchange `retencion.events` (claves `attendance.*`). En Render no hay RabbitMQ gestionado,
así que por defecto los eventos solo circulan dentro del monolito (con registro de publicaciones
en `event_publication`).

## Bienestar

`/api/v1/students/{id}/wellbeing/assessments` y `/referrals`: solo PSICOLOGO (ni siquiera
ADMIN), lecturas con `X-Access-Reason` y auditoría, resumen, hallazgos y motivos cifrados en
columna. Los demás módulos solo ven conteos (`WellbeingSignals`).

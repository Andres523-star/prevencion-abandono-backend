# Tiempo real, alertas, campanita, chat, intervenciones y retención (Fase 5)

Este documento describe los seis módulos de la Fase 5 y las decisiones que no se deducen del código.

## Mapa de módulos

| Módulo | Qué hace | API pública | Depende de |
|---|---|---|---|
| `realtime` | WebSocket + STOMP (`/ws`, SockJS de respaldo), autenticación del CONNECT, autorización de SUBSCRIBE/SEND, cierre de sesiones vencidas o revocadas | `RealtimePublisher`, SPI `SubscriptionAuthorizer` | common |
| `alerts` | Alertas de riesgo, deduplicación, asignación automática, escalamiento | `AlertAssigned` | risk, records, students, users, settings, audit |
| `notifications` | Campanita interna (sin correo), motivo legible, acciones "Abrir chat" y "Copiar enlace de cita" | solo REST/eventos | alerts, chat, interventions, retention, risk, records, justifications, students, users, academic, realtime, settings, audit |
| `chat` | Conversaciones docente-estudiante, mensajes cifrados, adjuntos escaneados, vista dividida | `ChatMessageSent` | academic, students, records, risk, users, realtime, settings, audit |
| `interventions` | Intervenciones con responsable, notas por rol, conversión del plan de apoyo, medición antes/después | `InterventionAssigned` | risk, academic, audit |
| `retention` | Casos de retención por etapas, seguimiento 7/15/30, cierre con causa y encuesta, embudo, campaña de reingreso | `RetentionFollowUpDue` | risk, records, students, users, settings, audit |

Los módulos de negocio nunca hablan STOMP: publican eventos de dominio y `notifications`/`chat`
entregan por `RealtimePublisher` **después de confirmar la transacción** (`@TransactionalEventListener`).
Si la transacción falla, no se empuja nada.

## Eventos asíncronos

Spring Modulith habilita `@EnableAsync`: cada `@ApplicationModuleListener` corre en su propio hilo
(virtual), en una transacción nueva, después de confirmar la transacción que publicó el evento. Dos
eventos del mismo estudiante pueden procesarse a la vez, por eso:

- `AlertService.raise` toma `pg_advisory_xact_lock` sobre la clave de deduplicación antes de buscar la
  alerta abierta (si no, ambos insertarían y uno fallaría en el índice único);
- `RetentionService` toma el mismo tipo de bloqueo por estudiante antes de abrir un caso (automático,
  autorreporte o consejero).

Si un listener falla, la publicación queda pendiente en `event_publication` (Modulith la registra en
INFO). Las pruebas esperan los listeners con `CampusIntegrationTest.awaitListeners()`.

## Tiempo real (`realtime`)

- **Endpoint:** `/ws` (SockJS) y `/ws/websocket` (WebSocket puro). Orígenes = los de CORS, sin `*`.
- **Autenticación:** cabecera nativa `Authorization: Bearer <access token de la plataforma>` en el
  frame `CONNECT`. Nunca por query string. El token se valida con el mismo `JwtDecoder` y conversor
  que la API REST (incluye la regla de MFA para roles privilegiados).
- **Suscripciones:** solo `/user/queue/**` (Spring la resuelve a las sesiones del propio usuario; el
  prefijo `/user/{otro}/...` se rechaza) y los temas que acepte algún `SubscriptionAuthorizer`
  (`/topic/chats.<id>` solo para participantes). Cualquier otro destino: frame `ERROR`.
- **SEND:** solo `/app/**`, con rate limit por usuario (`APP_REALTIME_SEND_PER_MINUTE`, 60/min).
- **Límites:** mensaje STOMP 64 KB, búfer de envío 512 KB, heartbeats 10 s.
- **Cierre:** `RealtimeSessions` revisa cada 15 s (`app.realtime.expiry-check-ms`) y cierra las
  conexiones cuyo token venció; además escucha `SessionRevoked` (logout, reutilización de refresh,
  cuenta desactivada, MFA retirado) y cierra al instante las conexiones de esa sesión o usuario.
- **Broker:** `APP_REALTIME_BROKER=simple` (una instancia; es el valor en Render) o `relay` (STOMP
  broker relay sobre RabbitMQ con `rabbitmq_stomp`, obligatorio con varias instancias).

Colas personales usadas: `notifications` (campanita), `chat` (mensajes y borrados),
`chat-receipts` (confirmaciones de lectura) y `errors` (errores de `/app/chat.send`).

## Alertas (`alerts`)

- **Origen:** `RiskAssessed` cuando el nivel sube o es el primer MEDIO/ALTO (alerta `RIESGO`,
  clave `RIESGO:<estudiante>`), `AttendanceThresholdReached` (`ASISTENCIA:<estudiante>:<grupo>`) y
  `SubjectLostByAbsences` (`PERDIDA:<estudiante>:<grupo>`).
- **Deduplicación:** índice único parcial sobre la clave para las alertas abiertas. Un evento nuevo
  con la misma clave actualiza la alerta (nivel, puntaje, factores) en vez de crear otra; solo se
  vuelve a notificar si el nivel subió.
- **Asignación:** a bienestar (PSICOLOGO con menos carga) si hay factores de bienestar; si no, al
  CONSEJERO del programa con menos alertas abiertas. Sin consejero, al COORDINADOR.
- **Escalamiento:** job horario (`app.alerts.escalation-cron`, también `POST /alerts/escalations/run`);
  NUEVA/VISTA sin atender tras `alerts.escalation-hours` (48) pasa al COORDINADOR del programa con
  menos carga (distinto del responsable actual) y sube `escalationLevel`; si no hay coordinador, la
  alerta sube de nivel igual y queda visible para ADMIN.
- **Contenido:** `anonCode`, programa, semestre, grupo, nivel, puntaje, factores y acciones sugeridas.
  La identidad no viaja en la alerta: se consulta aparte con `X-Access-Reason`.

## Campanita (`notifications`)

- Tabla `notifications` sin nombres: guarda `student_id` y el motivo estructurado (JSON con códigos
  y valores, p. ej. `{"subject":"Anatomía","average":2.6,"absences":3}`).
- **Docentes:** con riesgo MEDIO/ALTO suena la campanita del docente de cada materia con señal
  (nota bajo 3.0, tendencia BAJA o nivel de faltas). El motivo para el docente solo tiene claves
  académicas (`subject`, `groupId`, `average`, `trend`, `absences`, `maxAbsences`,
  `attendanceLevel`): nunca psicológicas, de salud ni socioeconómicas.
- **Lectura:** el nombre del estudiante se resuelve al listar, solo si el lector tiene alcance de
  lectura sobre el estudiante (ABAC), y la lectura queda auditada. El push en tiempo real nunca lleva
  el nombre.
- **Texto legible:** `ReasonText` arma frases como "Bajó su promedio a 2.6 en Anatomía y tiene 3 faltas".
- **Acciones del docente:** `openChat` (`POST /api/v1/chats` con el `studentId`) y `appointmentLink`.
- **Deduplicación:** no se repite la misma notificación (destinatario, tipo, estudiante, nivel y
  motivo) dentro de `notifications.dedupe-hours` (72). **Expiración:** `notifications.expiry-days` (30).

## Enlace de cita

`PUT /api/v1/users/me/appointment-link` (solo DOCENTE, sobre sí mismo). Validación estricta en
`AppointmentLinkPolicy`: solo `https`, sin usuario/contraseña ni puerto, host igual o subdominio de
la lista blanca `users.appointment-link-domains` (Google Calendar, Outlook, Microsoft Bookings),
máximo 500 caracteres, sin caracteres de control. `javascript:`, `data:`, `http:` y dominios
parecidos (`calendar.google.com.evil.com`) se rechazan. El backend solo guarda y entrega el enlace.

## Chat (`chat`)

- **Modelo:** `chat_conversations` (una por par docente-estudiante, `pair_key` único) con
  `chat_participants` (permite sumar consejero/psicólogo sin migración grande), `chat_messages`
  (texto cifrado, borrado lógico), `chat_attachments` y `chat_message_reports`. Mensajes paginados por
  keyset sobre el id (UUID v7).
- **Acceso:** solo un DOCENTE abre chat y solo con estudiantes inscritos (ACTIVA) en sus grupos de
  periodos no cerrados. El estudiante ve y responde sus conversaciones. Nadie más lee el chat;
  ADMIN usa `GET /admin/chats/{id}/messages` con `X-Access-Reason` (auditado).
- **Solo lectura:** si el estudiante ya no comparte grupo activo con el docente, enviar responde 409
  `chat_read_only`; el historial sigue disponible.
- **Envío:** `/app/chat.send` (STOMP) o `POST /chats/{id}/messages`; se valida participación,
  longitud (`chat.max-message-length`) y rate limit; se guarda y luego se entrega en `/user/queue/chat`.
- **Adjuntos:** `POST /chats/{id}/attachments` (multipart) **antes** de enviar el mensaje; responde un
  `attachmentId`, nunca una URL. Tipos por *magic bytes*: PDF, `.docx` (se rechazan `.doc`, `.docm`,
  `.dotm`, OLE y paquetes con `vbaProject.bin` o tipo de contenido *macroEnabled*) y audio (webm/opus,
  ogg, mp3, m4a/mp4) con duración declarada entre 1 y `chat.audio-max-seconds`. Tamaño máximo
  `chat.attachment-max-mb`. Antivirus **antes** de guardar: infectado → 422 `chat_file_infected` y no
  se almacena. Descarga solo por URL prefirmada de 5 minutos para participantes (o ADMIN), auditada.
- **Privacidad:** el texto y los adjuntos nunca se envían a la IA (el anonimizador no tiene ninguna
  ruta del chat en su lista blanca; lo prueba `ChatIT.chatTextNeverReachesTheAi`) ni aparecen en logs
  (`toString` de los DTO sin contenido). Retención configurable `chat.retention-days` (730): un job
  diario borra lógicamente los mensajes y elimina los archivos más antiguos.
- **Vista dividida:** `GET /chats/{id}/student-context` (docente dueño o ADMIN): perfil básico
  (código, nombre auditado, programa, semestre), materias compartidas con el docente (promedio,
  tendencia, faltas y política) y series para las gráficas (historial de nivel/puntaje, faltas por
  semana, notas por materia). Sin socioeconómico, bienestar ni documento.

## Intervenciones (`interventions`)

- Tipos TUTORIA, PSICOLOGIA, BECA, REUNION_PADRES, FLEXIBILIZACION, AYUDA_ECONOMICA, OTRO; estados
  PENDIENTE → EN_PROCESO → RESUELTA (resultado obligatorio).
- **Notas:** las de una intervención de PSICOLOGIA son siempre restringidas y solo las escribe y lee
  PSICOLOGO; en las demás, el autor puede marcarlas restringidas.
- **Plan de apoyo → intervenciones:** `POST /support-plans/{id}/convert` (CONSEJERO, PSICOLOGO,
  COORDINADOR) recibe las acciones editadas, aprueba el plan y crea una intervención por acción
  (`PlanActionMapping`). Un plan ya aprobado no se convierte dos veces (409).
- **Antes/después:** al crear se guarda el último análisis (`riskBefore`); al resolver se dispara un
  reanálisis (`trigger = INTERVENTION`, fuera de la transacción) y se guarda `riskAfter` y
  `scoreChange`.

## Retención (`retention`)

- **Apertura:** automática con `RiskAssessed` en el nivel `retention.auto-open-level` (ALTO), por
  autorreporte (`POST /students/me/retention-requests`; el mensaje queda como nota restringida y el
  estudiante solo recibe la confirmación) o por un consejero. Un solo caso abierto por estudiante.
- **Etapas:** DETECTADO → CONTACTADO (exige un intento de contacto con resultado CONTACTADO) →
  ENTREVISTA → PLAN_DE_APOYO → SEGUIMIENTO (exige al menos una tarea con fecha) → CERRADO. Sin saltos
  ni retrocesos (409).
- **Seguimiento:** al entrar a SEGUIMIENTO se programan 7, 15 y 30 días; un job diario (y
  `POST /retention/follow-ups/remind`) avisa al consejero por la campanita una sola vez por seguimiento.
- **Cierre:** RETENIDO (solo desde SEGUIMIENTO), APLAZADO (estudiante → APLAZADO), TRANSFERIDO o
  RETIRADO (causa del catálogo obligatoria; RETIRADO además encuesta de salida cifrada; estudiante →
  RETIRADO y causa registrada). El cambio de estado queda en la auditoría.
- **Notas de entrevista:** cifradas; solo el consejero asignado y PSICOLOGO.
- **Reingreso:** el primer día de cada mes se arma un **borrador** con los casos RETIRADO/APLAZADO
  cerrados hace al menos `retention.reentry-after-days` (30). No se contacta a nadie hasta que ADMIN
  o COORDINADOR lo aprueba; al aprobar se crean seguimientos REINGRESO y avisos al consejero.
- **Embudo:** `GET /retention/funnel?programId&termId` (detectados, contactados, en curso, retenidos,
  aplazados, transferidos, retirados) respetando el alcance por programa.

## Pruebas de aceptación

| Criterio | Prueba |
|---|---|
| Ciclo umbral → alerta → plan revisado → intervención → resolución → reanálisis | `AlertsInterventionsIT.thresholdToAlertToPlanToInterventionToReanalysis` |
| Deduplicación y escalamiento | mismo test + `notificationsAreDeduplicatedAndCanBeMarkedRead`, `AlertDomainTest` |
| Visibilidad de notas por rol | `AlertsInterventionsIT.psychologyNotesAreOnlyForPsychologists` |
| Transiciones de `retention_case` | `RetentionIT`, `RetentionDomainTest` |
| STOMP e2e: riesgo → campanita al docente correcto → chat con texto y adjunto | `RealtimeIT.riskReachesOnlyTheRightTeacherAndChatIsDeliveredToTheStudent` |
| Docente sin relación: sin notificación y 403 al abrir chat | mismo test + `ChatIT.onlyTheStudentsTeacherOpensAndOnlyParticipantsRead` |
| Notificación del docente sin factores psicológicos/socioeconómicos | `RealtimeIT` (claves del motivo) |
| No suscribirse a la cola ni al chat de otro | `RealtimeIT.cannotSubscribeToAnotherUsersQueueOrSendOutsideApp` y test e2e |
| CONNECT sin token o con token vencido | `RealtimeIT.connectWithoutTokenOrWithExpiredTokenIsRejected`, `StompAuthInterceptorTest` |
| Cierre por vencimiento y logout | `RealtimeIT.sessionsCloseWhenTheTokenExpiresOrOnLogout` |
| Adjuntos `.docm`, `.doc`, EICAR, extensión falsa | `ChatIT.attachmentsAreValidatedScannedAndDownloadedOnlyByParticipants`, `ChatDomainTest` |
| Enlace de cita con dominio no permitido o `javascript:` | `ChatIT.appointmentLinkIsValidatedAndOnlyEditableByTheTeacher`, `AppointmentLinkPolicyTest` |
| Ningún payload a la IA contiene texto del chat | `ChatIT.chatTextNeverReachesTheAi` |

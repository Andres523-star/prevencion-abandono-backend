# Backend: Plataforma de retención estudiantil (Java)

Cómo usar este archivo:
1. Guarda el **Contexto maestro** como `CLAUDE.md` en la raíz del repo. Claude Code lo lee en cada sesión.
2. Pega los prompts **uno por fase, en orden**. No pases a la siguiente fase hasta que los criterios de aceptación de la anterior pasen.
3. Al terminar cada fase, pídele a Claude Code: "ejecuta los tests, actualiza CLAUDE.md con las decisiones tomadas y haz commit".

---

## CONTEXTO MAESTRO (guardar como CLAUDE.md)

### Producto
Backend de una plataforma web para una universidad que **detecta, predice y previene la deserción estudiantil**. No es un CRUD: es un sistema de análisis, alerta e intervención.

Programas (6): Enfermería, Medicina, Derecho, Ingeniería de Software, Ingeniería Industrial, Odontología. Debe soportar decenas de miles de estudiantes (diseñar para 50.000 estudiantes activos y millones de registros de asistencia por semestre).

### Roles
ADMIN, CONSEJERO (consejero académico), PSICOLOGO (bienestar), COORDINADOR (de programa), DOCENTE (docente/tutor), ESTUDIANTE (portal propio, incluido en el alcance).

### Stack (no cambiar sin avisar)
- Java 21 (LTS), Spring Boot 3.x, Maven.
- Monolito modular con **Spring Modulith** (límites de módulo verificados por tests). Paquete raíz: `co.edu.retencion`.
- Spring Web (REST), Spring Security (OAuth2 Resource Server + JWT), Spring Data JPA/Hibernate, **Flyway** (migraciones), Bean Validation, MapStruct, Lombok (uso moderado).
- PostgreSQL 16, Redis (caché, rate limit), RabbitMQ (eventos y jobs asíncronos), MinIO/S3 (evidencias), ClamAV (antivirus de archivos).
- IA: SDK oficial de Anthropic para Java. Modelo configurable por propiedad (`ai.model.default`, `ai.model.triage`).
- Resiliencia: Resilience4j (circuit breaker, retry, rate limiter, bulkhead).
- Identidad: **Supabase Auth** (cualquier correo con verificación; opcionalmente Google, Microsoft u otros proveedores configurados en Supabase). El backend canjea el token de Supabase por su propia sesión. MFA con TOTP de Supabase (`aal2`); login local solo para 1-2 cuentas de emergencia (break-glass) de ADMIN.
- Correo transaccional (avisos de faltas, Fase 3): proveedor por definir detrás de un adaptador (SMTP/API tipo Resend, Brevo o SES; Microsoft Graph solo si la universidad usa M365). Plantillas Thymeleaf. Credenciales solo por variables de entorno. Los correos de cuenta (confirmación, recuperar contraseña) los envía Supabase.
- Tiempo real: **WebSocket con STOMP** (y SockJS como fallback). En producción con varias instancias: STOMP broker relay sobre RabbitMQ (plugin `rabbitmq_stomp`), nunca el broker simple en memoria.
- Hosting: **Render** (Blueprint `render.yaml`): PostgreSQL gestionado, Key Value (Redis) y backend en Docker, todos en la misma región. No se exige alojamiento en Colombia. La IA sigue recibiendo solo datos seudonimizados.
- Reportes: Apache POI (Excel) y OpenPDF (PDF).
- Observabilidad: Micrometer + Prometheus, logs JSON estructurados, OpenTelemetry.
- Docs: springdoc-openapi. Tests: JUnit 5, Mockito, **Testcontainers** (Postgres, Redis, RabbitMQ, MinIO), ArchUnit, WireMock.
- Docker Compose para desarrollo; GitHub Actions para CI.

### Reglas de arquitectura
- Cada módulo expone una API pública mínima (interfaces/eventos). Los demás módulos no acceden a sus repositorios ni entidades.
- Comunicación entre módulos por **eventos de dominio** (Spring Modulith events + outbox transaccional hacia RabbitMQ).
- Capas dentro del módulo: `api` (controllers/DTO), `application` (servicios/casos de uso), `domain`, `infrastructure`.
- Entidades JPA nunca salen por la API: siempre DTO (records de Java).
- IDs: UUID v7 (ordenables en tiempo). Fechas en UTC (`Instant`), zona de negocio America/Bogota.
- Errores con RFC 7807 (ProblemDetail). Paginación por keyset en tablas grandes.
- Soft delete en estudiantes y datos académicos; nunca borrado físico sin proceso formal de supresión.

### Reglas de seguridad y privacidad (NO NEGOCIABLES)
1. **Separación de identidad:** los datos personales (nombre, documento, correo, teléfono) viven en `student_identity`, cifrados a nivel de columna (AES-256-GCM, clave por envelope encryption con KMS o Vault). Todo lo demás se enlaza solo por `anon_code`.
2. **La IA nunca ve datos personales.** Solo `anon_code`, rangos y métricas. El texto libre (observaciones) NO se envía a la IA salvo que pase por el redactor de PII y se pueda desactivar por configuración. Los archivos de evidencia NUNCA se envían a la IA; solo metadatos (categoría, estado de revisión).
3. **Nota legal:** un código anónimo reversible es *seudonimización*, no anonimización total. Tratar siempre el flujo como datos personales (Ley 1581 de 2012 y Decreto 1377 de 2013, Colombia). Los datos psicológicos y de salud son **datos sensibles**.
4. Autenticación: Supabase Auth. El backend valida el token de Supabase (firma JWKS, issuer, audience `authenticated`, no anónimo), lo canjea una sola vez, exige **correo verificado** (`/auth/v1/user`), mapea al usuario interno y emite su propia sesión: JWT de acceso corto (10-15 min) + refresh token rotativo con detección de reutilización. **MFA obligatorio** para ADMIN, CONSEJERO, PSICOLOGO y COORDINADOR (claim `aal = aal2`). Los roles se asignan en la plataforma, nunca desde el token. Cualquier dominio de correo salvo que se restrinja con `APP_ALLOWED_EMAIL_DOMAINS`. Cuentas break-glass locales con argon2id + TOTP y bloqueo progresivo.
5. Autorización en dos niveles: RBAC (rol) + ABAC (alcance): el DOCENTE solo ve sus grupos; el COORDINADOR solo su programa; el ESTUDIANTE solo sus propios datos; solo PSICOLOGO lee evaluaciones psicológicas completas.
6. **Auditoría append-only** de toda escritura y de toda lectura de datos sensibles (quién, qué, cuándo, IP, motivo). La tabla de auditoría no permite UPDATE ni DELETE (permisos de BD + trigger).
7. Secretos fuera del repo (variables de entorno / Vault). Cabeceras de seguridad, CORS estricto, rate limiting por IP y por usuario, validación estricta de entrada, subida de archivos validada por *magic bytes*, tamaño y antivirus.
8. Los correos al estudiante NUNCA incluyen información psicológica, de salud ni de riesgo. Solo hechos académicos básicos (materia, fecha, contador de faltas) y un enlace al portal autenticado. **El correo se usa solo para avisos de faltas al estudiante.** Toda notificación al personal (docentes, consejeros, psicólogos, coordinadores) es **solo interna en la app (campanita)**, sin correo.
11. **Chat:** los mensajes y archivos del chat son datos personales: cifrados en reposo, acceso solo para los participantes (y ADMIN con motivo registrado en auditoría), retención configurable, y **nunca se envían a la IA**.
12. **Mínimo necesario por rol:** el DOCENTE ve nivel de riesgo y motivos **académicos** (notas y faltas) solo de estudiantes de sus grupos; nunca ve factores psicológicos, de salud ni socioeconómicos.
9. Logs sin PII (nunca registrar nombre, documento, correo, tokens ni contenido de evidencias).
10. Derechos del titular: soporte para consulta, rectificación y supresión de datos; política de retención configurable; registro de consentimiento y finalidad.

### Reglas del dominio de asistencia
- Por defecto, **con 4 faltas injustificadas en una materia se pierde por inasistencia**. El límite es configurable por programa y por materia (`attendance_policy`), no está hardcodeado.
- **La falta se cuenta por clase (sesión), no por horas.** Una sesión de 4 horas ausente = 1 falta.
- Cada programa define sus materias, grupos, horarios y estudiantes inscritos. Las sesiones de clase se **generan automáticamente desde el horario** y el periodo; cada sesión genera registro de asistencia.
- Cada falta notifica al estudiante por correo con el contador actualizado y la fecha límite para justificar.
- El estudiante justifica desde el portal con evidencias (PDF/JPG/PNG). **Solo el docente de la materia autoriza** la justificación (aprueba o rechaza con comentario). No hay aprobación automática ni de otro rol.
  - **Aprobada por el docente:** la falta se descuenta del contador.
  - **Rechazada o vencida:** cuenta como falta injustificada y queda en vigilancia.

### Reglas de notas
- Escala 0.0-5.0, **aprobación con 3.0**. No se modelan cortes ni porcentajes: la plataforma no calcula la nota final oficial (eso es del sistema académico), solo usa las notas registradas para detectar riesgo (promedio por materia y semestre, materias por debajo de 3.0, tendencia a la baja).
- Umbrales configurables (por defecto): aviso en la 2.ª falta, nivel crítico en la 3.ª, pérdida por inasistencia en la 4.ª.
- Al llegar a un umbral, el sistema dispara el análisis de IA y genera un **plan de apoyo** para que la universidad ayude al estudiante a no perder la materia. Las acciones siempre las aprueba y ejecuta un humano.

### Reglas del dominio de retención
- Existe el concepto de **caso de retención** (`retention_case`): se abre por detección automática, por autorreporte del estudiante ("estoy pensando en retirarme") o por un consejero.
- Etapas: DETECTADO → CONTACTADO → ENTREVISTA → PLAN_DE_APOYO → SEGUIMIENTO → cierre con resultado: RETENIDO, APLAZADO/RESERVA_DE_CUPO, TRANSFERIDO o RETIRADO (con causa y encuesta de salida).
- Seguimiento programado (7/15/30 días) y campaña de reingreso para retirados.

### Reglas de campanita (notificaciones internas) y chat
- Cuando el sistema detecta riesgo en un estudiante (por notas o faltas), **suena la campanita del docente** de las materias afectadas, en tiempo real. Sin correo.
- La notificación muestra al docente: nombre del estudiante, nivel de riesgo y **motivo legible** (ej.: "Bajó su promedio a 2.6 en Anatomía y tiene 3 faltas"). Desde ella el docente puede **Abrir chat** con el estudiante y **copiar el enlace de su cita** (agenda de citas que el docente ya tiene creada y registra en su perfil).
- Chat interno docente-estudiante en tiempo real: texto, audios, PDF y Word. Los archivos se suben al backend, se escanean y el mensaje lleva una referencia al archivo (no un enlace público).
- La vista del chat se divide en perfil del estudiante con sus gráficas de riesgo (izquierda) y conversación (derecha); el backend expone un endpoint que entrega ese contexto respetando el mínimo necesario por rol.

### Calidad
- Cobertura mínima 80% en servicios de dominio. Tests de integración con Testcontainers para cada endpoint.
- Test ArchUnit/Modulith que verifica límites de módulos.
- **Test obligatorio del anonimizador:** ningún payload hacia la IA contiene PII (nombres, documentos, correos, teléfonos).
- Cada migración Flyway es inmutable una vez aplicada. Cada fase termina con `./mvnw verify` en verde.

### Requisitos adicionales del equipo
- **Las indicaciones de fases futuras que mencionen Microsoft Entra ID, Microsoft Graph u Oracle Cloud Bogotá quedan reemplazadas** por Supabase Auth, un proveedor de correo por definir y Render, respectivamente.
- Código limpio y comentado (Javadoc en español explicando el *porqué*).
- Aplicar los **5 patrones creacionales** (Singleton, Factory Method, Abstract Factory, Builder, Prototype) y los estructurales **Proxy, Decorator y Adapter**. Mapa actualizado en `docs/patrones-de-diseno.md`; cada clase que implementa un patrón lo declara en su Javadoc.
- Dejar rastro del uso de IA: nota "Asistido por IA (Claude, Anthropic)" en `package-info.java` de cada módulo y archivos de infraestructura y registro por fase en `BITACORA_IA.md`. **Los commits no llevan `Co-Authored-By` ni ninguna mención a la IA como autora o colaboradora**: los commits de las Fases 1 a 5 son de Andres523-star (decisión del equipo, 2026-11-04) y, desde el 2026-11-10 (Fases 6 y 7 en adelante), el autor es **nicolas0867**, con su correo privado de GitHub (`203567366+nicolas0867@users.noreply.github.com`), y el push se hace desde esa cuenta. Lo ya publicado no se reescribe.
- Despliegue en **Render** (Blueprint `render.yaml`) con PostgreSQL gestionado para muchos usuarios. Sin requisito de alojamiento en Colombia (decisión del equipo, 2026-11-03).

### Estado: Fase 1 completada (2026-11-03) — decisiones tomadas
- **Versiones:** Spring Boot 3.5.16, Spring Modulith 1.4.14, Java 21, PostgreSQL 16. `./mvnw verify` verde: 55 pruebas unitarias/arquitectura + 62 de integración (2 de Redis se omiten sin Docker; corren en CI).
- **Módulos:** `common` es `Type.OPEN` (infraestructura transversal). La API pública de cada módulo es su paquete raíz; los subpaquetes (`api`, `application`, `domain`, `infrastructure`) son internos. Dependencias: auth → users, settings, audit; users/settings → audit; audit → common.
- **Pruebas sin Docker:** `TestDatabase` usa Testcontainers si hay Docker y si no PostgreSQL 16 embebido (Zonky). Se crea el rol `retencion_app` con privilegios mínimos; Flyway migra como dueño.
- **Supabase Auth (Fase 1.1, reemplaza a Entra ID):** `POST /api/v1/auth/supabase/exchange` recibe el `access_token` de supabase-js. Validación local con JWKS (ES256/RS256) o `SUPABASE_JWT_SECRET` (HS256 heredado); anti-replay con `OneTimeTicketStore.claim` (SET NX, TTL hasta el `exp`); `GET /auth/v1/user` (con `apikey`) para `email_confirmed_at` y sesión viva, con circuit breaker `supabase`. `APP_REQUIRE_VERIFIED_EMAIL=true` por defecto. Usuarios enlazados por `external_issuer` + `external_subject`, o por correo verificado si fueron precreados. Roles nunca desde el token; usuario nuevo sin roles salvo `APP_BOOTSTRAP_ADMIN_EMAILS` o rol por dominio (prohibido asignar por dominio un rol con MFA).
- **Sesión:** JWT HS256 propio (15 min máx., validado al arrancar) + refresh opaco (solo hash SHA-256 en BD), familia = id de sesión, vida absoluta 24 h, rotación con bloqueo de fila y revocación de la familia ante reutilización. Al renovar se re-evalúan estado y MFA. El conversor de JWT descarta roles privilegiados si `mfa=false` (defensa en profundidad).
- **Break-glass:** tabla `local_credentials`; argon2id (m=19 MiB, t=2, p=1), TOTP RFC 6238 ±1 paso con anti-replay, bloqueo progresivo leído de `app_settings` en cada intento; errores genéricos y hash de relleno contra enumeración.
- **ABAC:** `ScopeAuthorizationService` (`@abac` en SpEL) + `@RequireScope`; SPI `ResourceScopeResolver` por tipo de recurso y `TeachingAssignmentProvider` (vacío hasta la Fase 2). Reglas: COORDINADOR/CONSEJERO por programa; PSICOLOGO transversal salvo que tenga programas; DOCENTE por grupos; ESTUDIANTE titular. Falla cerrado. Alcance por programa en `user_program_scopes` (llevan JWT `programs`).
- **Auditoría:** `audit_log` particionada por mes (+ DEFAULT), `seq` continuo bajo bloqueo de `audit_chain_head`, hash SHA-256 encadenado con forma canónica `len:valor;`, trigger que rechaza UPDATE/DELETE/TRUNCATE (también en cada partición) y REVOKE al rol de la app. Escritura en `REQUIRES_NEW`; lecturas sensibles fallan si no se pueden auditar. Motivo por cabecera `X-Access-Reason`. Regla ArchUnit: todo POST/PUT/PATCH/DELETE lleva `@Audited`. Denegaciones 403 auditadas vía `SecurityDeniedEvent`.
- **Cifrado:** formato `v1.` + Base64(cabecera autenticada como AAD + iv + ciphertext). KMS local (clave por entorno, rotación con `APP_CRYPTO_PREVIOUS_KEYS`) o adaptador Vault Transit; proxy de caché de DEK. Blind index HMAC-SHA256 con clave propia y contexto por tipo (EMAIL, DOCUMENT, PHONE).
- **Settings:** claves sembradas por migración; solo se editan existentes, mismo tipo JSON y versión optimista (412). Proxy de caché (TTL 30 s, invalidación por `SettingChanged` al confirmar).
- **Web:** errores RFC 7807 con `type=urn:retencion:problem:<code>` y campo `code`; `fail-on-unknown-properties`; CSP `default-src 'none'`, HSTS, Permissions-Policy, COOP/CORP; CORS sin comodines (falla al arrancar); rate limit por IP, por usuario y estricto en `/api/v1/auth/`; IP del cliente con `ClientIpResolver` (hops de proxy de confianza) y Tomcat RemoteIpValve en prod. Logs JSON con `MaskingJsonGeneratorDecorator`.
- **Outbox:** registro de publicaciones JPA de Spring Modulith (`event_publication`, `completion-mode: delete`). La externalización a RabbitMQ se activa cuando haya consumidores (Fase 3).

### Pendientes / riesgos identificados en la Fase 1
- Fase 3: elegir proveedor de correo transaccional (reemplaza a Microsoft Graph) y configurar SMTP propio en Supabase para los correos de cuenta.
- Fase 7: aunque no se exige alojamiento en Colombia, la Ley 1581 sigue aplicando al tratamiento (consentimiento, finalidad, transferencia internacional a Render/Supabase).
- Revocación inmediata del access token tras logout (hoy vive hasta 15 min); necesaria para cerrar WebSockets en la Fase 5.
- Integración Spring Modulith → RabbitMQ (outbox externalizado), MinIO y ClamAV: infraestructura en Compose, código en Fases 3 y 5.
- Adaptador de KMS de la nube final (OCI Vault) y rotación programada de claves.
- En Render hay un solo usuario de BD: crear rol de aplicación separado si el plan lo permite.
- La auditoría serializa escrituras (una cadena global); si el volumen lo exige, pasar a cadenas por partición.

### Estado: Fase 2 completada (2026-11-03) — decisiones tomadas
- **Verificación:** `./mvnw verify` verde: 74 pruebas unitarias/arquitectura + 94 de integración (incluye importación de 10.000 filas en segundo plano). Cobertura de dominio ≥ 80% en todos los módulos.
- **Módulos nuevos:** `academic` (es el "academic-structure" del enunciado: los paquetes Java no admiten guiones), `students` e `imports`. Dependencias: academic → users; students → academic, users; imports → academic, students, users. `users` valida programas con la SPI `common.reference.ProgramReferences` (la implementa academic) para no crear un ciclo. `academic` implementa `TeachingAssignmentProvider`: el ABAC del docente se resuelve en cada petición (grupos de periodos no cerrados), no va en el JWT.
- **Migración V2** (`V2__estructura_academica_y_estudiantes.sql`). Programas sembrados con IDs fijos tipo v7: ENF `…0001`, MED `…0002`, DER `…0003`, ISW `…0004`, IIN `…0005`, ODO `…0006` (`0195f000-0000-7000-8000-00000000000N`). La tabla de grupos se llama `course_groups` (`group` es palabra reservada). Columnas numéricas `integer` (Hibernate valida tipos).
- **Calendario:** festivos de Colombia calculados (`ColombianHolidays`: fijos, Ley Emiliani y Pascua; `POST /holidays/colombia/{año}`). Registrar un festivo o una suspensión (global o por programa) cancela las sesiones PROGRAMADAS de esas fechas.
- **Sesiones:** `SessionPlanner` (dominio puro) + inserción JDBC por lotes `ON CONFLICT DO NOTHING` sobre (grupo, fecha, hora inicio): idempotente. Cambiar el horario borra las sesiones futuras PROGRAMADAS y regenera desde hoy (las pasadas y dictadas no se tocan). Transiciones: PROGRAMADA → DICTADA | CANCELADA (con motivo), CANCELADA → PROGRAMADA; una sesión DICTADA no cambia. Una sesión cuenta como UNA falta sin importar su duración.
- **Políticas de asistencia:** tabla `attendance_policies` con alcance PROGRAM, PROGRAM_CLINICAL (grupos `PRACTICA_CLINICA`, solo en programas clínicos ENF/MED/ODO) o SUBJECT. Resolución: materia > práctica clínica > programa > defecto (4 faltas, aviso 2, crítico 3, 3 días hábiles, tardanzas no suman). Reglas fijas expuestas en `EffectiveAttendancePolicy` (`countsPerSession`, `justificationRequiresTeacherApproval`). Invariante: 1 ≤ aviso < crítico < máximo ≤ 50. API pública para la Fase 3: `AcademicDirectory.effectivePolicy(groupId)`.
- **Estudiantes:** `anon_code` = `EST-` + 10 caracteres Base32 Crockford con `SecureRandom` (no derivable). Identidad en `student_identities` cifrada; blind index del documento (normalizado, único) y del correo. `StudentView` (DTO público) no tiene identidad (prueba por reflexión). Identidad solo para ADMIN/CONSEJERO/PSICOLOGO/COORDINADOR con `X-Access-Reason` y auditoría; el estudiante ve lo suyo en `/students/me`; el docente ve nombres solo en la lista de clase de sus grupos (auditada, sin motivo obligatorio). Perfil socioeconómico solo ADMIN/CONSEJERO/PSICOLOGO (nunca docente ni coordinador). Borrado lógico.
- **ABAC de estudiante:** resolver STUDENT = programa + grupos con inscripción ACTIVA + usuario titular. Listados filtrados por alcance (coordinador/consejero: sus programas; psicólogo sin programas y admin: todo). Buscar por documento fuera de alcance responde 404 (no revela existencia).
- **Inscripciones:** únicas por (estudiante, grupo), cupo protegido con `pg_advisory_xact_lock` por grupo; retirar = estado RETIRADA (no se borra); reinscribir reactiva. Solo estudiantes ACTIVO.
- **Vínculos de usuario:** docente = `course_groups.teacher_user_id` (debe tener rol DOCENTE); coordinador = `user_program_scopes`; estudiante = `students.user_id`, automático por blind index del correo (evento `UserProvisioned` → `StudentUserLinker`, que otorga ESTUDIANTE) o manual (`PUT /students/{id}/user`). Usuarios creados por ADMIN antes del primer ingreso se vinculan al entrar con ese correo verificado. Un correo de usuario borrado no puede volver a entrar ni recrearse (409).
- **Importación:** 5 tipos con clave natural (ver `docs/importacion.md`); cada fila en su propia transacción (REQUIRES_NEW); ≤ 256 KB en la petición, más grande en cola con `FOR UPDATE SKIP LOCKED` y reencolado de huérfanos; archivo borrado al terminar; reporte sin valores; formato por *magic bytes*; mapeo configurable (`app.imports.aliases` y parámetro `mapping`). Solo ADMIN.
- **Datos sintéticos:** `SyntheticDataGenerator` (Datafaker, es-CO, correos `.test`); en dev con `APP_SYNTHETIC_ENABLED=true` genera 50.000 estudiantes si la BD está vacía; nunca en `prod`. Estructura por API pública de academic; estudiantes e inscripciones por lotes JDBC cifrando igual que la entidad.

### Pendientes / riesgos identificados en la Fase 2
- Validar con datos reales el formato de importación y ajustar alias (`app.imports.aliases`).
- Revisar si el blind index de documento debe incluir el tipo (hoy un mismo número con distinto tipo colisiona).
- No se valida que el periodo del grupo esté abierto al inscribir (la API pública de academic no expone el estado del periodo por id).
- Prueba de rendimiento formal de la generación de 50.000 estudiantes y de las sesiones del periodo (se probó a escala reducida).
- Importación por coordinador limitada a su programa (hoy solo ADMIN).

### Estado: Fase 3 completada (2026-11-04) — decisiones tomadas
- **Verificación:** `./mvnw verify` verde: 99 pruebas unitarias/arquitectura + 109 de integración (2 de Redis se omiten sin Docker). Cobertura de dominio ≥ 80%. Prueba de volumen a escala completa: 5.063.823 filas de asistencia, p95 ≤ 14,2 ms en todas las consultas de la app (ver `docs/rendimiento.md`).
- **Módulos nuevos:** `records` (es el "academic-records" del enunciado), `justifications` ("attendance-justification"), `mailing` y `wellbeing`. Dependencias: records → academic, students, settings; justifications → records, academic, students; mailing → records, justifications, students, settings; wellbeing → students; imports → records (`GradeBook`). Infraestructura compartida de archivos en `common.files` (almacenamiento, antivirus, *magic bytes*) para reutilizarla en el chat (Fase 5).
- **Migración V3** (`V3__registro_academico_justificaciones_y_correo.sql`). `attendance_records` particionada por `LIST (term_id)` con PK `(id, term_id)`; la partición del periodo la crea `attendance_ensure_partition` (SECURITY DEFINER, bloqueo consultivo) antes de la primera escritura; partición DEFAULT de respaldo. Índices: (term, enrollment), (term, session), (student, term) y parcial de faltas sin justificar por fecha límite. También amplía el CHECK de `import_jobs` con `GRADES`.
- **Asistencia:** clave única (periodo, sesión, inscripción): una sesión = una falta sin importar su duración. Ventana de edición en settings (`attendance.edit-window-hours` = 48) desde el fin de la sesión (Bogotá); después solo COORDINADOR/ADMIN con `X-Access-Reason` (409 `attendance_edit_window_closed` / 400 `attendance_correction_reason_required`). No se toma asistencia de sesiones futuras o canceladas; registrar marca la sesión como DICTADA. Una falta con justificación ya revisada no se puede cambiar.
- **Contador:** `AbsenceCounter` se recalcula desde los registros (`AbsenceTally`, dominio puro) con bloqueo pesimista de la fila en cada cambio; cuenta injustificadas + pendientes + tardanzas/`tardiesPerAbsence`. Los eventos `AttendanceThresholdReached` (AVISO/CRITICO) y `SubjectLostByAbsences` (PERDIDA) se publican **solo cuando el nivel sube** respecto al anterior; si baja (p. ej. por una justificación aprobada) y vuelve a subir, se publican de nuevo.
- **Plazo de justificación** en días hábiles (sin fines de semana ni festivos registrados), calculado al registrar la falta y guardado en el registro.
- **Justificaciones:** una por falta; solo el estudiante titular la envía y adjunta evidencias; solo el docente **actual y asignado** del grupo decide (cualquier otro rol, incluido ADMIN, recibe 403); rechazar exige comentario; no se decide con evidencias en revisión antivirus (409). `GroupTeacherChanged` reasigna las pendientes. Job diario 00:05 Bogotá marca VENCIDA (y `POST /justifications/expire`). Descripción y comentario cifrados en columna.
- **Evidencias:** *magic bytes* (PDF/JPG/PNG) + extensión coherente + tamaño (`APP_EVIDENCE_MAX_SIZE`, 5 MB) + antivirus **antes** de guardar (infectado → 422 `file_infected`, nunca se almacena). Antivirus caído → estado ERROR, reescaneo cada 5 min. Clave aleatoria `evidencias/<uuid v7>-<ext>`. Descarga solo por URL prefirmada de 5 min con `@SensitiveRead`. Almacenamiento `local` (dev/test, URL firmada con HMAC) o `s3` (MinIO, Supabase Storage, R2). Antivirus `clamav` (prod; servicio privado en `render.yaml`) o `signature` (solo EICAR; dev/test).
- **Correo (único uso):** outbox propio `email_outbox` (no se guarda la dirección: se resuelve al enviar; solo el blind index para rebotes). `AbsenceRecorded` → aviso con `not_before` = fin de la ventana de edición o la hora del resumen diario (`mail.absence-mode`, `mail.digest-hour`); `AbsenceRetracted` lo cancela; `JustificationDecided` → aviso de decisión. Al enviar se revalida la falta y se elige la plantilla por el contador actual. Despacho con `FOR UPDATE SKIP LOCKED` + lease, backoff exponencial, errores permanentes sin reintento, rebotes por webhook HMAC (`X-Mail-Signature`) con supresión. Proveedores por Factory Method: `logging`, `resend`, `graph`, `memory` (tests). Contenido limitado a materia, fecha, contador, límite, plazo y enlace; nunca categoría, descripción ni comentario de la justificación.
- **RabbitMQ:** opcional (`APP_RABBIT_ENABLED`, apagado por defecto porque Render no lo ofrece gestionado). Con él, los eventos `@Externalized` salen al exchange `retencion.events` tras confirmar la transacción.
- **Notas:** escala 0.0–5.0 con un decimal (no se redondea: se rechaza 2.95), clave natural inscripción + fecha + descripción, indicadores (promedio por materia y semestre, materias < 3.0, tendencia SUBE/ESTABLE/BAJA con delta ±0.3 entre las 2 últimas y las anteriores). Importación `GRADES`.
- **Observaciones y causas:** observaciones cifradas; el docente solo ve las suyas. Catálogo `dropout_causes` (13 sembradas, editable por ADMIN); causas de categoría SALUD visibles solo para PSICOLOGO/ADMIN.
- **Bienestar:** solo PSICOLOGO (ni ADMIN), lecturas con motivo obligatorio y auditoría, texto cifrado. API pública `WellbeingSignals` solo con conteos (para la Fase 4).
- **Pruebas:** `MutableClock.setNow` permite simular la fecha de cada sesión; el escenario `AttendanceWorld` crea un periodo relativo a hoy para que las pruebas no dependan de la fecha de ejecución.

### Pendientes / riesgos identificados en la Fase 3
- Definir el proveedor de correo real (Resend vs. Graph) y verificar el dominio remitente (SPF/DKIM/DMARC); conectar el webhook de rebotes del proveedor elegido a `/api/v1/mail/bounces` (adaptar el formato de su payload).
- ClamAV en Render necesita ~2 GB de RAM (servicio privado `standard`); evaluar costo o un escáner externo.
- Correo a estudiantes sin correo de contacto queda FALLIDO sin aviso alternativo (se verá en el portal y la campanita, Fase 5/7).
- La apelación de una justificación rechazada se modela en la Fase 7 (hoy una justificación por falta).
- Cuando el docente edita una falta ya avisada por correo no se envía rectificación.
- Probar la prueba de volumen sobre PostgreSQL gestionado de Render (hoy en PostgreSQL embebido local).

### Estado: Fase 4 completada (2026-11-04) — decisiones tomadas
- **Verificación:** `./mvnw verify` verde: 125 pruebas unitarias/arquitectura + 128 de integración (2 de Redis se omiten sin Docker). Cobertura de dominio ≥ 80%. La API de Claude se simula con WireMock (`FakeClaude`: JSON válido, inválido, timeout, 429, 5xx, rechazo, secuencias de *tool use*); el modelo ONNX de las pruebas se construye en el test (`OnnxTestModels`, regresión logística) y corre en ONNX Runtime real.
- **Módulos nuevos:** `risk` ("risk-engine") y `ai` ("ai-analysis"). Dependencias: risk → ai, records, students, academic, wellbeing, settings, justifications; ai → settings. El agente usa la SPI `ai.RetentionAgentTools`, que implementa risk (sin ciclo). APIs públicas nuevas: `records.StudentRecords` (foto académica sin PII) y evento `records.GradeRecorded`; `StudentRegistry` suma `enrollmentsOf`, `studentIdsEnrolledInTerm`, `activeStudentIds` (keyset por programa), `socioeconomic` y `givenName` (solo para `{{nombre}}`, lectura auditada); `AcademicDirectory` suma `findTerm`, `listTerms` y `listPrograms`.
- **Migración V4** (`V4__motor_de_riesgo_e_ia.sql`): `support_resources` (11 sembrados, id = código), `risk_assessments` (append-only: REVOKE UPDATE/DELETE a la app), `risk_student_state`, `risk_work_queue`, `support_plans`, `ai_token_usage`, `ai_calls`, `agent_runs`, `agent_tool_calls`, `ml_models`, `ml_dataset_exports`, tablas `BATCH_*` de Spring Batch 5.2 (con GRANT de secuencias) y 22 parámetros en `app_settings` (reglas, umbrales, interruptores de IA). Hashes en `varchar(64)` (Hibernate rechaza `char` al validar).
- **Diseño híbrido:** `RiskScorer` (`RulesScorer` / `MlScorer`) → `RiskExplainer` (Claude) → `RiskOrchestrator`. `scoreType` RULES (puntaje 0-100, `probability = null`, `scoreMeaning` lo aclara) o ML_CALIBRATED (única probabilidad). `source` RULES / HYBRID / AI (AI solo si `risk.ai-can-escalate`, apagado: la IA nunca baja el nivel ni decide). Política de IA: manual siempre (modelo capaz si MEDIO/ALTO); evento y lote si nivel ≥ `ai.min-level` (MEDIO) o umbral CRÍTICO/PÉRDIDA; modelo capaz si ALTO o umbral crítico. Cualquier fallo → análisis de reglas con `fallbackReason`.
- **Reglas** (objetos JSON en settings, sin redeploy, versión = hash de la configuración): promedio bajo, materias < 3.0, faltas por nivel de la política de cada materia, tardanzas, tendencia, conducta, bienestar (conteo), causas (salud aparte como BIENESTAR), contexto socioeconómico solo con señal académica/asistencia. **El estrato no suma nunca** (ninguna regla lo lee; probado) y solo se usa para elegibilidad de recursos y métricas de sesgo.
- **Privacidad hacia la IA:** lista blanca de rutas (`PayloadWhitelist.RISK_PAYLOAD`) + detector de PII sobre cada texto, revalidado justo antes de enviar; bandas de promedio y personas a cargo; sin estrato; recursos sin contacto. Texto libre solo con `ai.free-text.enabled` (por defecto false) y redactado (`PiiRedactor`: regex de correo/URL/@usuario/teléfono/documento + diccionario `ai/nombres-comunes.txt`; palabras ambiguas solo con mayúscula; apellidos tras un nombre). Bienestar y causas de salud solo con `ai.payload.include-sensitive-signals` (false); con ellas, el texto de la IA solo lo ve PSICOLOGO. La salida también se valida (esquema, PII, recursos citables, marcadores).
- **Cliente Claude:** SDK `anthropic-java` 3.4.0; `ai.model.default = claude-opus-5-5`, `ai.model.triage = claude-haiku-5-5`, esfuerzo medium/low; salida estructurada (`output_config.format`) + validación local con JSON Schema estricto (networknt; al proveedor se envía el esquema sin restricciones de longitud/rango); 1 reintento por salida inválida; *fallback* del servidor ante rechazos (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`) solo con el modelo capaz. Decoradores: presupuesto diario (`ai.daily-token-budget`, tope blando por día de Bogotá) → bulkhead/rate limiter/circuit breaker `anthropic` → adaptador del SDK (reintentos del SDK ante 429/5xx). Se guardan `aiModel`, `promptVersion` (`ai/prompts/*.v1.txt`), `payloadHash` y tokens; `ai_calls` sin contenido.
- **Eventos sin bloquear al docente:** los listeners solo encolan en `risk_work_queue` (ANALYZE con antirrebote `risk.event-debounce-seconds`, una fila por estudiante; SUPPORT_PLAN por inscripción y nivel); `RiskWorker` (cada 15 s, `FOR UPDATE SKIP LOCKED`, reintentos con espera exponencial) analiza fuera de la petición. Umbral o justificación rechazada → vigilancia (`risk.watch-days`).
- **Planes de apoyo:** prototipo por nivel (`SupportPlanTemplates`); desde `ai.support-plan.min-level` (CRÍTICO) los redacta la IA; siempre PROPUESTO hasta que CONSEJERO/COORDINADOR/PSICOLOGO aprueba o rechaza (comentario obligatorio). Marcadores `{{nombre}}`/`{{materia}}` (`ai.StudentTextPlaceholders`); el nombre solo se rellena en `/support-plans/{id}/rendered` (requiere `X-Access-Reason`, auditado). Un plan nuevo de mayor nivel reemplaza al propuesto.
- **Lote nocturno:** Spring Batch `nightlyRiskAnalysis` (01:30 Bogotá), partición por programa (parámetro opcional `programs`), lector keyset con reinicio, filtro por hash del payload (solo cambiados o en vigilancia), `ResourcelessTransactionManager` en el paso (cada análisis con transacción corta; la IA nunca con conexión tomada), skip de errores.
- **Mínimo necesario al leer:** `FactorVisibility` — DOCENTE solo ACADEMICO/ASISTENCIA y nunca texto de la IA; COORDINADOR sin socioeconómico ni bienestar; CONSEJERO/ADMIN sin bienestar; PSICOLOGO todo; ESTUDIANTE 403 (su vista llega en la Fase 7). Lecturas del historial auditadas (`@SensitiveRead` sin motivo obligatorio).
- **Agente de retención:** `ai.agent.enabled` (false). Bucle manual de *tool use* (máx. `ai.agent-max-iterations`), herramientas de solo lectura sin parámetro de estudiante (`get_academic_summary`, `get_attendance`, `get_risk_history`, `search_resources`) y `submit_draft` validado con JSON Schema; recurso inventado → error de herramienta; cada llamada en `agent_tool_calls`; solo borradores.
- **Modelo propio:** dataset seudonimizado (`POST /ml/dataset-exports`, ADMIN, motivo, `row_id` = HMAC de un solo uso, etiqueta = no inscrito en el periodo siguiente, columnas `audit_*` solo para métricas por grupo); `ml/train.py` (LightGBM + calibración isotónica, validación temporal, AUC/recall/Brier/ECE/curva por programa y banda de estrato, ONNX sin ZipMap); registro con validación de variables (prohibidas: estrato, trabajo, transporte, económico, salud, edad, género, `audit_*`), carga en ONNX Runtime y SHA-256; comparación (`ModelComparison`) y aprobación con anulación explícita; activación por `risk.ml.active-version` y vuelta atrás poniendo la versión anterior. Documentado en `docs/motor-de-riesgo.md` y `docs/modelo-predictivo.md`.
- **Sesgo:** `GET /risk/bias-metrics` (ADMIN/CONSEJERO/COORDINADOR por programa): distribución del último nivel por programa, banda de estrato y semestre, celdas < 10 suprimidas, brecha máxima de "% ALTO" entre bandas.

### Pendientes / riesgos identificados en la Fase 4
- Revisión jurídica: el envío seudonimizado a la API de Anthropic es transmisión/transferencia internacional (Ley 1581); confirmar consentimiento y finalidad antes de activar `ai.payload.include-sensitive-signals` o `ai.free-text.enabled`.
- Probar el cliente contra la API real con una clave de pruebas (el formato de `output_config`, el beta de *fallbacks* y el conteo de tokens solo se probaron con WireMock).
- Validar con datos reales la etiqueta de deserción del dataset y la aproximación del semestre histórico; entrenar y comparar el primer modelo.
- Ajustar los pesos por defecto de las reglas con el equipo académico (hoy son una propuesta razonada) y ampliar el diccionario de nombres con listados reales.
- El presupuesto de tokens es un tope blando (puede excederse por las llamadas en curso).
- El lote nocturno a 50.000 estudiantes no se midió a escala completa (prueba funcional con un programa).
- La Fase 5 consumirá `RiskAssessed` (alertas, campanita) y convertirá los planes aprobados en intervenciones.

### Estado: Fase 5 completada (2026-11-05) — decisiones tomadas
- **Verificación:** `./mvnw verify` verde: 143 pruebas unitarias/arquitectura + 143 de integración (2 de Redis se omiten sin Docker). Cobertura de dominio ≥ 80%. El e2e de tiempo real (`RealtimeIT`) usa un cliente STOMP real (`WebSocketStompClient`) contra el servidor en puerto aleatorio (contexto propio). Detalle en `docs/tiempo-real-y-retencion.md`.
- **Módulos nuevos:** `realtime`, `alerts`, `notifications`, `chat`, `interventions` y `retention`. Dependencias: realtime → common; alerts → risk, records, students, users, settings; chat → academic, students, records, risk, users, realtime, settings; interventions → risk, academic; retention → risk, records, students, users, settings; notifications → todos los anteriores + justifications, academic. APIs públicas nuevas: `common.security.SessionRevoked`; `users.UserDirectory.activeUsersWithRole/appointmentLink`; `students.StudentRegistry.displayName/changeStatus`; `records.StudentRecords.weeklyAbsences/grades` y `records.CauseRegistry`; `justifications.JustificationSubmitted`; `risk.RiskQueries` (último análisis, historial, resumen sin texto de IA), `risk.RiskAnalysis` (reanálisis con `RiskTrigger.INTERVENTION`) y `risk.SupportPlans` (aprobar con acciones editadas).
- **Migración V5** (`V5__alertas_campanita_chat_intervenciones_retencion.sql`): `users.appointment_link`, CHECK de `risk_assessments.trigger_type` con INTERVENTION, tablas de alertas (índice único parcial de la clave de deduplicación abierta), notificaciones, chat (conversación + participantes + mensajes + adjuntos + reportes), intervenciones y notas, retención (casos con un solo abierto por estudiante, contactos, notas de entrevista, tareas, seguimientos, campañas) y 10 parámetros en `app_settings`.
- **Tiempo real:** `/ws` con SockJS (`/ws/websocket` puro). JWT de la plataforma en la cabecera nativa `Authorization` del CONNECT (mismo `JwtDecoder` y conversor que REST, nunca query string). SUBSCRIBE solo a `/user/queue/**` o temas aceptados por un `SubscriptionAuthorizer` (falla cerrado); SEND solo a `/app/**` con rate limit por usuario. Mensaje 64 KB, heartbeats 10 s. `RealtimeSessions` (Decorator del manejador WebSocket) cierra conexiones con token vencido (cada 15 s) y al instante ante `SessionRevoked` (logout, reutilización de refresh, cuenta desactivada). Broker `simple` por defecto (Render, una instancia) o `relay` sobre RabbitMQ (`APP_REALTIME_BROKER`). Los empujes salen solo tras confirmar la transacción.
- **Listeners asíncronos (hallazgo):** Spring Modulith habilita `@EnableAsync` (`AsyncEnablingConfiguration`), así que todo `@ApplicationModuleListener` corre en otro hilo (virtual) tras confirmar, no en el hilo de la petición. Por eso la deduplicación de alertas y la apertura de casos se serializan con `pg_advisory_xact_lock` por clave/estudiante, y las pruebas esperan a los listeners: `EventListenerAwaiter` (sin publicaciones pendientes en `event_publication`) se ejecuta antes y después de cada petición de MockMvc y al inicio de cada prueba, en todas las fases; las que fallan se registran y no se vuelven a esperar.
- **Alertas:** clave de deduplicación `RIESGO:<est>` / `ASISTENCIA|PERDIDA:<est>:<grupo>`; un evento con la misma clave actualiza la abierta y solo re-notifica si sube el nivel. Asignación por menor carga: PSICOLOGO si hay factores de bienestar, si no CONSEJERO del programa, si no COORDINADOR. Escalamiento horario tras `alerts.escalation-hours` (48) al COORDINADOR. Descartar exige nota. La identidad no viaja en la alerta.
- **Campanita:** tabla sin nombres (`student_id` + motivo estructurado JSON); el nombre se resuelve al leer solo con alcance ABAC y auditado; el push nunca lo lleva. Docente: solo claves académicas (materia, promedio, tendencia, faltas, límite, nivel de asistencia) y texto por plantilla (`ReasonText`). Acciones `openChat` y `appointmentLink`. Deduplicación 72 h por (destinatario, tipo, estudiante, nivel, motivo) y expiración 30 días. Sin correo al personal.
- **Enlace de cita:** solo DOCENTE sobre sí mismo; `https`, sin userinfo ni puerto, dominio en lista blanca `users.appointment-link-domains` (o subdominio), ≤ 500.
- **Chat:** una conversación por par (`pair_key`), participantes en tabla aparte (listo para consejero/psicólogo). Solo el docente abre y solo con estudiantes con inscripción ACTIVA en sus grupos de periodos no cerrados; sin grupo compartido queda de solo lectura (409 `chat_read_only`). Texto cifrado en columna; ADMIN lee solo con `X-Access-Reason`. Adjuntos subidos antes del mensaje (devuelven id, nunca URL): PDF, `.docx` (rechaza `.doc`/OLE, `.docm`/`.dotm`, `vbaProject.bin` y tipo *macroEnabled*) y audio (webm, ogg, mp3, m4a) con duración declarada 1..`chat.audio-max-seconds`; antivirus antes de guardar (422 `chat_file_infected`); descarga por URL prefirmada de 5 min para participantes, auditada. Retención `chat.retention-days` (730) con job diario. Vista dividida `GET /chats/{id}/student-context` sin socioeconómico, bienestar ni documento. El chat no tiene ruta en la lista blanca de la IA (probado).
- **Intervenciones:** PENDIENTE → EN_PROCESO → RESUELTA (resultado obligatorio); notas de PSICOLOGIA siempre restringidas y solo para PSICOLOGO. `POST /support-plans/{id}/convert` edita, aprueba y crea una intervención por acción (`PlanActionMapping`); antes/después del riesgo con reanálisis fuera de la transacción al resolver.
- **Retención:** apertura automática (≥ `retention.auto-open-level`, ALTO), por autorreporte (el estudiante solo ve la confirmación; su mensaje queda como nota restringida) o por consejero. Etapas de una en una: CONTACTADO exige contacto exitoso y SEGUIMIENTO al menos una tarea; seguimiento a 7/15/30 días con recordatorio único por la campanita. Cierre RETENIDO solo desde SEGUIMIENTO; RETIRADO/TRANSFERIDO con causa del catálogo (RETIRADO + encuesta cifrada) y cambio de estado del estudiante auditado. Campaña de reingreso mensual en BORRADOR hasta aprobación de ADMIN/COORDINADOR. Embudo por programa y periodo con alcance.

### Pendientes / riesgos identificados en la Fase 5
- La campanita puede duplicar una notificación si dos eventos idénticos llegan a la vez (la deduplicación es por consulta, sin índice único); impacto bajo.
- El relay STOMP sobre RabbitMQ no se probó (sin Docker en local; Render no ofrece RabbitMQ gestionado): con varias instancias hay que desplegar RabbitMQ con `rabbitmq_stomp` y probar `APP_REALTIME_BROKER=relay`.
- La duración del audio la declara el cliente; falta medirla en el servidor (p. ej. con ffprobe) si se exige con rigor.
- El rate limit de STOMP usa el mismo `RateLimiter` (memoria/Redis); revisar la capacidad con carga real (Fase 7).
- El estudiante aún no tiene endpoint propio de campanita/chat más allá de los generales; su portal completo es de la Fase 7.
- Las notificaciones no tienen preferencia de silencio por usuario ni agrupación (posible mejora de UX).
- La conversación admite participantes adicionales en el modelo, pero aún no hay API para sumar consejero/psicólogo.

### Estado: Fase 6 completada (2026-11-05) — decisiones tomadas
- **Verificación:** `./mvnw verify` verde: 159 pruebas unitarias/arquitectura + 163 de integración (3 de Redis se omiten sin Docker). Cobertura de dominio ≥ 80%. Carga a escala completa (50.000 estudiantes, 7.477.085 filas de asistencia): p95 de 17 ms con Gatling (12.000 peticiones, 100/s, 0 fallidas); sin caché, la consulta más lenta tiene p95 de 62 ms. Ver `docs/rendimiento.md` y `docs/analitica-y-reportes.md`.
- **Módulos nuevos:** `analytics`, `dashboard` y `reports`. Dependencias: analytics → academic, records, justifications, retention, risk, settings; dashboard → analytics, alerts, notifications, risk, students, academic; reports → analytics, risk, students, records, academic, settings; imports → analytics. APIs públicas nuevas: `analytics.AnalyticsQueries/AnalyticsFilter/AnalyticsViewer/DropoutHistoryBook`; `risk.RiskQueries.page/levelCounts`; `students.StudentRegistry.findByIds/displayNames`; `records.StudentRecords.nearLimit`; `alerts.AlertQueries`; `notifications.NotificationFeed`; evento `retention.RetentionCaseChanged`; `common.state.SharedCache`; `ObjectStorage.put(key, Path, mediaType)`.
- **Migración V6** (`V6__analitica_dashboard_y_reportes.sql`): `dropout_history`, vistas `analytics_student_facts`, `analytics_attendance_cube` y `analytics_retention_cube`, `analytics_refresh_state`, función `analytics_refresh()` (SECURITY DEFINER), `report_jobs`, tipo de importación `DROPOUT_HISTORY` y 5 parámetros en `app_settings`.
- **Modelo de lectura:** la analítica solo lee vistas materializadas (el cruce entre módulos se hace al refrescar, en la base de datos). Deserción = estado RETIRADO sin cierre TRANSFERIDO (decisión del equipo, 2026-11-06; migración V7), contada solo en el último periodo del estudiante (`is_dropout`), con fecha del cierre del caso o, si no hay, de la última actualización. Los transferidos siguen en la población y se ven aparte en el embudo; un retirado sin caso cuenta como deserción. Población = estudiantes con alguna inscripción. Semestre y nivel de riesgo son los actuales. `analytics_attendance_cube` parte de `absence_counters`, no de `attendance_records`.
- **Refresco e invalidación:** los eventos solo marcan `dirty` (el `UPDATE` escribe la primera vez); un job cada 30 s refresca si pasó `analytics.refresh-min-seconds` (300), y a las 02:30 siempre. `REFRESH CONCURRENTLY` con bloqueo consultivo entre instancias. Caché por generación: la clave incluye generación, endpoint y hash de filtros + alcance + mínimo de celda, y guarda la respuesta ya suprimida. Cada respuesta trae `refreshedAt`.
- **Supresión:** `CellSuppressor` oculta conteos entre 1 y `analytics.min-cell-size` − 1 (10); el cero se muestra. Supresión complementaria en columnas con total conocido. Celda oculta = `null` + `suppressed: true`. El tablero no suprime porque es para personal con acceso a esos estudiantes.
- **Alcance:** `AnalyticsViewer.of(user)`: ADMIN todo; PSICOLOGO sin programas transversal; demás, sus programas (sin programas, 403). DOCENTE y ESTUDIANTE no acceden a analítica. El alcance va explícito en la API porque los reportes se generan sin petición.
- **Series históricas:** se cortan antes del primer periodo en vivo; con `riskLevel` o `termId` no aparecen; cada punto trae `source` (LIVE, HISTORICAL, MIXED).
- **Tablero:** `/dashboard/summary` y `/dashboard/students` (keyset). Personal: totales del modelo de lectura. Docente: en vivo, solo activos de sus grupos, sin alertas ni % de abandono. Listas sin nombre ni factores, lecturas auditadas. ESTUDIANTE 403 (Fase 7).
- **Reportes:** `report_jobs` con `FOR UPDATE SKIP LOCKED`, lease de 15 min y 3 intentos; el alcance y los filtros se guardan al pedir. Tipos GENERAL, BY_PROGRAM, BY_SEMESTER, BY_YEAR, AT_RISK_STUDENTS y ATTENDANCE. `includeIdentity` solo en listados y con `X-Access-Reason`; se audita solicitud, generación (una fila por reporte) y descarga. Listados sin factores psicológicos, de salud ni socioeconómicos. Aviso con Ley 1581 en todo reporte; PDF con marca de agua. Excel SXSSF (ventana de 100 filas), PDF con máximo `reports.pdf-max-rows` (20.000) o falla con `report_too_large`. Vencen a las `reports.expiry-hours` (24): el archivo se borra y la descarga responde 410. Un reporte ajeno responde 404.
- **Pruebas:** `AnalyticsIntegrationTest` genera un periodo de 1.200 estudiantes una sola vez y filtra por él. El Excel de 100.000 filas corre en una JVM aparte con 48 MB de heap. `perf/` es un proyecto Maven aparte (Gatling) para no mezclar sus dependencias con el backend.

### Pendientes / riesgos identificados en la Fase 6
- La etiqueta del modelo predictivo (no inscrito en el periodo siguiente) aún cuenta a los transferidos como deserción: definir si se excluyen antes de entrenar el primer modelo.
- La analítica puede tener hasta ~5 minutos de atraso, y el refresco tarda ~38 s a escala completa: revisar en PostgreSQL gestionado.
- `SharedCache` sobre Redis no se probó en local (sin Docker): `RedisStateFactoryIT` corre en CI. La prueba de carga usó la caché en memoria.
- La prueba de volumen cubre un periodo; con años de historia crecerán las consultas sin filtro de periodo.
- La supresión protege cada respuesta, no la comparación de muchas consultas con filtros distintos.
- Semestre y nivel de riesgo son los actuales, también al mirar periodos anteriores: guardar una foto por periodo si se necesita exactitud histórica.
- Los reportes grandes usan disco temporal del servicio: revisar el espacio en Render.
- Si el histórico de un mismo mes trae filas por semestre y sin semestre, se suman: la plantilla pide usar una sola forma.

### Estado: Fase 7 completada (2026-11-10) — decisiones tomadas
- **Verificación:** `./mvnw verify` verde: 166 pruebas unitarias/arquitectura + 178 de integración (3 de Redis se omiten sin Docker). Cobertura de dominio ≥ 80%. Restauración de backup probada (`BackupRestoreIT`). Pico de asistencia a escala completa: 612 tomas de lista de 200 docentes en 5 minutos sobre 50.000 estudiantes, 0 fallas, p95 de 110 ms, partición y contadores exactos (ver `docs/rendimiento.md`). La indicación de Oracle Cloud Bogotá queda reemplazada por Render.
- **Módulos nuevos:** `portal` (API del portal del estudiante, solo lectura y siempre de sus datos, resuelto desde su usuario) y `privacy` (derechos del titular y registro de actividades de tratamiento). Dependencias: portal → students, records, academic, users, risk, ai; privacy → students, records, justifications, academic, users, settings, audit; chat y notifications escuchan eventos de privacy. APIs públicas nuevas: `justifications.JustificationQueries` y `JustificationAppealed`; `privacy.DataRequestSubmitted/DataRequestAnswered/StudentDataSuppressed`; `records.StudentRecords.absences` y `AttendanceLedger.reopenJustification`; `risk.SupportPlans.approvedForStudent`; `students.StudentRegistry.identityOf/consentsOf/suppressPersonalData`; `users.UserDirectory.displayName/deactivate`.
- **Migraciones V8 y V9:** V8 agrega la apelación (estado APELADA, texto cifrado), `data_subject_requests` (una abierta por tipo y estudiante), tipos nuevos de la campanita y 6 parámetros. V9 reemplaza `attendance_ensure_partition` por una versión que tolera la carrera entre instancias.
- **Portal:** `/portal/overview` (materias con docente, enlace de cita, faltas con la política que aplica y cuántas le quedan, notas), `/portal/absences` (historial con plazo y si aún puede justificar), `/portal/support` (solo planes **aprobados por una persona**, con su mensaje, y servicios de la universidad). Nunca nivel, puntaje, probabilidad ni factores; las faltas en cifras ("Te queda 1 falta antes del límite"). Las acciones viven en su módulo (justificar, apelar, "necesito ayuda", campanita, chats, consentimientos, solicitudes de datos).
- **Apelación:** una vez, dentro de `justifications.appeal-days` (5) desde la decisión; la falta vuelve a PENDIENTE (sigue contando), avisa al docente (`JUSTIFICACION_APELADA`) y decide el docente actual del grupo con el mismo endpoint; esa decisión es definitiva.
- **Derechos del titular:** consulta (copia JSON en el acto, solo el titular la descarga por URL de 10 min, vence a las `privacy.export-expiry-hours`), rectificación (detalle obligatorio; se ejecuta registrando lo corregido) y supresión (dos ADMIN distintos: aprueba uno, ejecuta otro; borra identidad y perfil socioeconómico, desvincula y desactiva el usuario, purga chats y campanita; lo académico queda seudonimizado). Plazos en días hábiles (10 consulta, 15 reclamo), rechazo con motivo, auditoría de cada paso, alerta si vencen. Registro de actividades en `privacy/registro-actividades.json` (`GET /privacy/processing-activities`).
- **Retención:** jobs nuevos para `email_outbox` cerrados (`mail.outbox-retention-days`, 180) y `ai_calls` (`ai.calls-retention-days`, 365), más los existentes. Tabla completa y lista de validación jurídica en `docs/cumplimiento-ley-1581.md`.
- **Endurecimiento:** `SecurityMatrixIT` recorre la especificación OpenAPI y llama todos los endpoints sin token (401 salvo las rutas públicas declaradas), sin rol y como estudiante (nunca 2xx fuera de lo permitido, nunca 5xx); encontró y se cerró `GET /interventions/mine` abierto a cualquier autenticado. `RequestSizeLimitFilter` (1 MB para JSON, 413). Pruebas de inyección y de respuestas sin enumeración (`HardeningIT`). maven-enforcer (sin SNAPSHOT ni rangos, plugins con versión) y SBOM CycloneDX (`target/bom.json`) escaneado por Trivy en CI. Imagen con etiquetas OCI y `apk upgrade`. Revisión ASVS L2 en `docs/seguridad-asvs.md`.
- **Hallazgos del pico de asistencia (corregidos):** (1) los listeners asíncronos corrían en hilos virtuales sin límite y agotaban el pool → `AsyncExecutorConfig` (6 hilos + cola, `APP_ASYNC_LISTENER_THREADS`); (2) la partición del periodo se creaba dentro de la transacción de la primera toma de lista y el bloqueo hacía esperar a las demás con su conexión tomada → se crea antes de la transacción (`preparePartition`) y V9 tolera la carrera; (3) bulkhead de Resilience4j para la toma de lista (`APP_ATTENDANCE_MAX_CONCURRENT` 10, espera 60 s, 503 con `Retry-After`); (4) la toma de lista carga los registros de la sesión en una consulta, crea los contadores que faltan de una vez y solo recalcula el contador cuando la marca afecta las faltas.
- **Observabilidad:** `OperationalGauges` (eventos pendientes, bandeja de correo por estado, cola de riesgo, reportes, alertas abiertas, solicitudes vencidas; conteos con caché de 15 s), contador `risk.assessments`, readiness con base y Redis en prod, tablero de Grafana provisionado, alertas de Prometheus enlazadas al runbook, recolector OTel que borra atributos de usuario (`observability/`, `docker-compose.observability.yml`).
- **Backups:** `ops/backup/backup.sh` (pg_dump custom + gpg AES-256 + SHA-256) y `restore.sh` (a una base nueva). `BackupRestoreIT` corre los scripts reales: mismas filas por tabla, mismo último hash de auditoría, mismos triggers append-only, vistas de analítica. Política y RPO/RTO en `docs/backups.md`.
- **Despliegue:** `MigrateMain` (Flyway sin Spring) en el `preDeployCommand` de Render; en prod la app solo valida el esquema (`APP_FLYWAY_MIGRATE_ON_STARTUP=false`, `FlywayStartupConfig`). `render.yaml`: despliegue por deploy hook (`autoDeployTrigger: off`) y **una instancia** mientras el tiempo real use el broker simple. CD en `.github/workflows/deploy.yml` (staging automático tras CI; producción manual con aprobación). Réplica de lectura opcional para analítica (`APP_ANALYTICS_READ_URL`).
- **Documentación final:** arquitectura C4 (Mermaid), catálogo de eventos, portal, cumplimiento, ASVS, observabilidad, backups, runbook (incluida la brecha de datos personales), dimensionamiento, despliegue, checklist de piloto, guía de datos reales y manual de configuración.

### Pendientes / riesgos identificados en la Fase 7
- Hacer push y correr CI en GitHub: las pruebas con Docker (Redis, relay STOMP) y los escaneos solo corren allí.
- Crear los proyectos de Render (staging y producción), cargar secretos y deploy hooks; repetir la prueba de carga y una restauración real sobre PostgreSQL gestionado.
- La cadena global de auditoría aparece como contención en el pico de asistencia: evaluar cadenas por partición.
- Escalar a varias instancias exige RabbitMQ con `rabbitmq_stomp` (`APP_REALTIME_BROKER=relay`).
- Validación jurídica (lista en `docs/cumplimiento-ley-1581.md`), registro en el RNBD, contratos con encargados, oficial de protección de datos.
- Política institucional de retención del registro académico, del historial de riesgo y de bienestar (hoy sin depuración masiva).
- La etiqueta del modelo predictivo sigue contando a los transferidos como deserción (pendiente de la Fase 6).
- Revocación inmediata del access token REST tras logout (los WebSockets sí se cierran al instante).

---

## FASE 1: Fundaciones y seguridad base

```
Lee CLAUDE.md. Vamos a construir la Fase 1: fundaciones y seguridad base. Aún no hay dominio académico.

Crea el proyecto Maven (Java 21, Spring Boot 3.x, Spring Modulith) con paquete raíz co.edu.retencion y estos módulos: common, auth, users, audit, settings. Entrega:

1. Docker Compose con PostgreSQL 16, Redis, RabbitMQ, MinIO y ClamAV, más perfiles dev/test/prod. Configuración por variables de entorno, sin secretos en el repo; incluye .env.example.
2. Flyway con la migración inicial: users, roles, user_roles, refresh_tokens, audit_log (particionada por mes, sin UPDATE/DELETE para el rol de aplicación) y app_settings.
3. Autenticación con Microsoft Entra ID (Microsoft 365 institucional): flujo OIDC Authorization Code + PKCE, validación del token de Entra (issuer, audience, tenant permitido), aprovisionamiento/vinculación del usuario interno por correo institucional (solo dominios permitidos), emisión de sesión propia (JWT de acceso corto + refresh token rotativo con detección de reutilización), logout. MFA obligatorio para ADMIN, CONSEJERO, PSICOLOGO y COORDINADOR verificado con el claim `amr` (si falta, rechaza con error claro). Cuentas break-glass locales con argon2id + TOTP y bloqueo progresivo. Configura el tenant y client id por variables de entorno y documenta el registro de la aplicación en Entra ID. Para dev/test, usa un IdP simulado (WireMock o Keycloak en Testcontainers).
4. RBAC + ABAC: roles ADMIN, CONSEJERO, PSICOLOGO, COORDINADOR, DOCENTE, ESTUDIANTE; anotaciones/servicio de autorización por alcance (programa, grupo, propietario del dato) reutilizable por los demás módulos.
5. Auditoría: interceptor/aspecto que registra escrituras y lecturas marcadas con @SensitiveRead (usuario, acción, entidad, IP, motivo, hash encadenado para detectar manipulación).
6. Infraestructura de cifrado: AttributeConverter AES-256-GCM con envelope encryption (interfaz KmsProvider con implementación local para dev y adaptador para Vault/KMS), y "blind index" HMAC para buscar por documento sin descifrar.
7. Seguridad transversal: rate limiting (Bucket4j + Redis), cabeceras de seguridad, CORS estricto, manejo de errores ProblemDetail, logs JSON sin PII, correlación por request-id, health checks y métricas Micrometer.
8. CI en GitHub Actions: build, tests, OWASP Dependency-Check o Trivy, escaneo de secretos.
9. Swagger/OpenAPI con esquema de seguridad.

Criterios de aceptación: ./mvnw verify en verde con Testcontainers; tests de login, refresh con reutilización detectada, MFA, bloqueo y autorización por rol; test que demuestra que audit_log rechaza UPDATE/DELETE; test ArchUnit/Modulith de límites. Al final, actualiza CLAUDE.md con las decisiones tomadas y resume lo pendiente.
```

---

## FASE 2: Estructura académica, usuarios y estudiantes

```
Lee CLAUDE.md. Fase 2: estructura académica y estudiantes. Crea los módulos academic-structure y students.

1. academic-structure: Program (seed con los 6 programas: Enfermería, Medicina, Derecho, Ingeniería de Software, Ingeniería Industrial, Odontología), Term (periodo académico), Subject (materia, créditos, semestre sugerido, prerrequisitos), Group (materia + periodo + docente + cupo), ClassSchedule (día, hora inicio/fin, aula) y ClassSession (sesión concreta generada a partir del horario, con estado PROGRAMADA/DICTADA/CANCELADA).
   - Generación automática de ClassSession a partir del horario y las fechas del periodo, con soporte de festivos y suspensión de clases.
   - AttendancePolicy por programa y por materia (maxAbsences=4 por defecto, umbrales de aviso 2 y 3, ventana de justificación en días hábiles, si las tardanzas acumuladas equivalen a falta). Reglas fijas: la falta cuenta por sesión (no por horas) y la falta justificada se descuenta solo con aprobación del docente. La política de la materia sobrescribe la del programa. Debe poder administrarse por API.
   - Soporte para prácticas/rotaciones clínicas (Medicina, Enfermería, Odontología) como tipo de grupo con política propia.
2. students: Student (anon_code único, programa, semestre actual, estado ACTIVO/RETIRADO/APLAZADO/GRADUADO, fecha de ingreso), StudentIdentity en tabla separada con campos cifrados (nombre, documento con blind index, correo, teléfono), SocioeconomicProfile (estrato, trabaja, dependientes, transporte; sin identificadores), Enrollment (estudiante, grupo, periodo) y consentimiento de tratamiento de datos (versión, fecha, finalidad).
   - Los DTOs de lectura que incluyen identidad solo se devuelven a roles autorizados y generan registro de auditoría.
   - Generación de anon_code no predecible y no derivable de datos del estudiante.
3. Gestión de usuarios: CRUD de usuarios y asignación de roles; vínculo usuario-docente, usuario-estudiante y usuario-coordinador (con su programa).
4. Importación masiva por CSV/Excel (estudiantes, docentes, materias, grupos, inscripciones): validación fila por fila, modo "dry-run" con reporte de errores, procesamiento asíncrono para archivos grandes, idempotencia por clave natural, y reporte descargable de resultados. Los datos reales aún no tienen formato definido: define un formato de plantilla documentado y deja el mapeo configurable.
5. Índices pensados para el volumen (estudiante+periodo, grupo+fecha) y un script de datos sintéticos (Instancio/Datafaker) para generar 50.000 estudiantes de prueba en el perfil dev.

Criterios de aceptación: tests de integración por endpoint, tests de ABAC (un coordinador no ve otro programa; un docente solo sus grupos; un estudiante solo lo suyo), test de que ningún DTO público de Student expone identidad, importación probada con 10.000 filas. Actualiza CLAUDE.md.
```

---

## FASE 3: Registro académico, asistencia, correos y justificaciones

```
Lee CLAUDE.md, especialmente "Reglas del dominio de asistencia". Fase 3: módulo academic-records (notas, asistencia, observaciones) y módulo attendance-justification.

1. Notas: Grade por inscripción (escala 0.0-5.0, aprobación 3.0, con fecha y descripción opcional de la evaluación). SIN cortes ni porcentajes: esta plataforma no calcula la nota oficial. Calcula indicadores de riesgo: promedio simple por materia y por semestre, materias por debajo de 3.0 y tendencia (comparación de las últimas notas). Endpoints para registro por docente (individual y por lote por grupo) e importación desde el sistema académico, con control de alcance.
2. Asistencia:
   - El docente toma asistencia por ClassSession (PRESENTE, FALTA, TARDANZA) individual y por lote por grupo. Ventana de edición configurable (por defecto 48 h) tras la cual solo coordinador/admin pueden corregir con auditoría.
   - AbsenceCounter por inscripción (injustificadas, pendientes de revisión, justificadas, tardanzas) actualizado de forma transaccional y recalculable. Debe respetar AttendancePolicy (peso por sesión, tardanzas, si la justificada cuenta).
   - Tabla de asistencia particionada por periodo.
3. Justificaciones:
   - Entidades AbsenceJustification (categoría: SALUD, FAMILIAR, LABORAL, TRANSPORTE, OTRO; descripción; estado PENDIENTE/APROBADA/RECHAZADA/VENCIDA; revisor; comentario) y JustificationEvidence (clave en MinIO, mime, tamaño, sha256, estado del escaneo antivirus).
   - El estudiante sube evidencias desde su portal (PDF/JPG/PNG, tamaño máximo configurable, validación por magic bytes, escaneo ClamAV antes de quedar disponible, nombres de archivo aleatorios, descarga solo por URL prefirmada de corta duración y con auditoría).
   - El docente de la materia es el ÚNICO que autoriza: ve las justificaciones pendientes de sus grupos y aprueba (la falta se descuenta del contador) o rechaza con comentario obligatorio. Ningún otro rol aprueba ni hay aprobación automática. Si el grupo cambia de docente, las pendientes pasan al nuevo docente.
   - Job de vencimiento: pasada la ventana, la justificación pendiente sin enviar pasa a VENCIDA y la falta cuenta como injustificada.
4. Correos de faltas al estudiante (outbox transaccional + RabbitMQ + Microsoft Graph sendMail desde el buzón institucional; en tests, un adaptador falso). Es el ÚNICO uso de correo en toda la plataforma:
   - Al registrar una falta se emite AbsenceRecorded. El correo sale tras la ventana de edición o en un resumen diario (configurable) para evitar enviar correos por errores del docente.
   - El correo incluye materia, fecha, contador actual de faltas, límite, fecha límite para justificar y enlace al portal autenticado. NADA de información de riesgo, psicológica ni de salud.
   - Reintentos con backoff, manejo de rebotes, plantilla Thymeleaf por tipo de aviso (1.ª falta, aviso en umbral, crítico, pérdida por inasistencia, justificación aprobada/rechazada), registro de entrega.
5. Eventos de dominio: AttendanceThresholdReached (con nivel) y SubjectLostByAbsences. Esta fase solo los publica; los consumirán las fases 4 y 5.
6. Observaciones de docentes/tutores (tipo académica/conducta) y evaluaciones físicas/psicológicas (módulo wellbeing, acceso restringido a PSICOLOGO con auditoría de lectura, cifrado de columna, remisiones).
7. Catálogo de causas de riesgo/retiro (DropoutCause) y asociación estudiante-causa.

Criterios de aceptación: test e2e del flujo completo (docente marca falta → correo encolado → estudiante justifica con evidencia → antivirus → docente aprueba/rechaza → contador correcto → evento de umbral publicado); test de que solo el docente del grupo puede aprobar (otro docente, coordinador y admin reciben 403); test de que una sesión larga cuenta como una sola falta; tests de políticas distintas por programa; test de que el correo no contiene datos sensibles; test de que un archivo con extensión engañosa o malware de prueba (EICAR) es rechazado; prueba de rendimiento con carga de asistencia de 5 millones de filas. Actualiza CLAUDE.md.
```

---

## FASE 4: Motor de riesgo e IA

```
Lee CLAUDE.md, especialmente las reglas de privacidad. Fase 4: módulos risk-engine y ai-analysis.

Diseño híbrido de tres capas, desacopladas por interfaces:
- RiskScorer (interfaz) con implementación RulesScorer ahora, y una futura MlScorer (modelo entrenado exportado a ONNX y servido con ONNX Runtime para Java). Hoy el "score" es un puntaje de riesgo calculado por reglas, no una probabilidad calibrada; el DTO debe indicar `scoreType` (RULES | ML_CALIBRATED).
- RiskExplainer (IA con Claude): explica factores y genera recomendaciones.
- Un orquestador que decide cuándo llamar a cada uno.

1. Motor de reglas configurable desde settings (sin redeploy): promedio bajo, materias perdidas o en riesgo, faltas injustificadas y cercanía al límite de la política de cada materia, tardanzas, tendencia de notas, observaciones de conducta, evaluaciones de bienestar, causas registradas, factores socioeconómicos usados con cuidado de sesgo (el estrato NO debe castigar automáticamente: documenta y testea esa decisión). Cada regla devuelve su aporte y su explicación.
2. Anonimizador: construye el payload desde `anon_code` y métricas/rangos, usando una lista blanca de campos (nunca lista negra). Redactor de PII para texto libre (regex + diccionario de nombres) y flag para excluir texto libre por completo. Test obligatorio de ausencia de PII.
3. Cliente Claude (SDK Anthropic para Java): modelo por propiedad; triage con modelo económico para análisis masivo y modelo más capaz para casos críticos/retención. Salida JSON validada con JSON Schema: nivel (ALTO/MEDIO/BAJO), factores detectados con peso, causas probables, recomendaciones concretas y el campo `confidence`. Reintento si el JSON es inválido, circuit breaker, rate limiter, presupuesto diario de tokens configurable y fallback al motor de reglas si la API falla. Guarda modelVersion, promptVersion, hash del payload y tokens usados.
4. Base de conocimiento de servicios de la universidad (RAG simple): catálogo configurable de recursos (tutorías, monitorías, becas, apoyo psicológico, bienestar, ayudas de transporte/alimentación, flexibilización académica, etc.) con elegibilidad y contacto. Las recomendaciones de la IA deben referenciar recursos reales del catálogo por id, no inventarlos.
5. Plan de apoyo por asistencia: al consumir AttendanceThresholdReached, genera un plan específico para ayudar al estudiante a no perder la materia (ej.: nivelación, ajuste de horario, apoyo de transporte, contacto con docente, remisión a bienestar), con prioridad y responsable sugerido. Es una propuesta: un humano la aprueba. Los textos dirigidos al estudiante usan placeholders ({{nombre}}) que el backend rellena; la IA nunca recibe el nombre.
6. Análisis: manual (`POST /students/{id}/analyze`), por evento (cambio de notas/asistencia/umbral) y nocturno por lotes (Spring Batch con particionado) solo para estudiantes activos cuyo payload cambió (hash) o que estén en vigilancia. Cada análisis es una fila nueva en risk_assessment (historial y evolución): nivel, score, scoreType, factores, recomendaciones, fuente (RULES/AI/HYBRID), versiones.
7. Agente de retención (opcional dentro de esta fase, desactivable por flag): usando tool use, Claude puede consultar herramientas SOLO LECTURA sobre datos anonimizados (resumen académico, asistencia, historial de riesgo, catálogo de recursos) para redactar un borrador de plan de retención. El agente nunca envía mensajes ni ejecuta acciones; solo produce borradores para revisión humana. Registra cada llamada a herramienta.
8. Guardarraíles: la IA recomienda, no decide; toda salida muestra sus factores; revisión humana obligatoria antes de cualquier acción; panel/endpoint de métricas de sesgo básicas (distribución de riesgo por programa, estrato, semestre).
9. Modelo predictivo propio con el historial real de deserción (ya disponible y autorizado):
   - Exportación del dataset seudonimizado (features + etiqueta de deserción por periodo), comando restringido a ADMIN y auditado.
   - Entrenamiento fuera de línea de un modelo clásico (gradient boosting tipo XGBoost/LightGBM, o Tribuo si se quiere 100% Java), con validación temporal (entrenar con periodos anteriores, probar con los más recientes), calibración de probabilidades y métricas (AUC, recall en el grupo de riesgo alto, curva de calibración) por programa.
   - Exportación a ONNX e implementación de MlScorer con ONNX Runtime para Java. Con el modelo activo, el score pasa a `scoreType=ML_CALIBRATED` y es el único que se muestra como "probabilidad". Claude sigue explicando y recomendando.
   - Registro de versión del modelo, fecha de entrenamiento y métricas; comparación del nuevo modelo contra el anterior antes de activarlo (activación por configuración, con vuelta atrás).
   - Revisión de sesgo: el modelo no debe usar estrato ni variables socioeconómicas para castigar; documentar qué variables entran y revisar el desempeño por grupo.

Criterios de aceptación: tests con WireMock simulando la API (JSON válido, inválido, timeout, 429); test de fallback; test de PII; test de que cada análisis genera una fila de historial; test de que un cambio de umbral en settings cambia el resultado sin redeploy; test de que el modelo no recibe campos fuera de la lista blanca. Actualiza CLAUDE.md.
```

---

## FASE 5: Alertas, notificaciones, intervenciones y retención

```
Lee CLAUDE.md, especialmente "Reglas del dominio de retención" y "Reglas de campanita y chat". Fase 5: módulos realtime, alerts, notifications, chat, interventions y retention.

0. Infraestructura de tiempo real (módulo realtime):
   - WebSocket con STOMP, endpoint /ws con SockJS como fallback. Broker simple solo en dev; en prod, STOMP broker relay sobre RabbitMQ para que funcione con varias instancias.
   - Autenticación en el frame CONNECT con el JWT de la plataforma (ChannelInterceptor), nunca por query string. Autorización en SUBSCRIBE y SEND: un usuario solo se suscribe a su cola personal (/user/queue/...) y a los chats donde es participante.
   - Límite de tamaño de mensaje STOMP, rate limit por usuario, heartbeats, orígenes permitidos estrictos (sin "*"), cierre de sesión WebSocket cuando el token expira o se revoca.

1. Alertas: se generan cuando el nivel de riesgo sube o cruza un umbral, o ante AttendanceThresholdReached/SubjectLostByAbsences. Contenido: anon_code (la identidad solo se resuelve para roles autorizados), carrera, semestre, grupo, nivel, score, factores y acciones sugeridas. Deduplicación, estados NUEVA/VISTA/ATENDIDA/DESCARTADA, asignación automática por programa (consejero) o a bienestar si hay factores psicológicos, escalamiento si no se atiende en N horas (configurable).

2. Notificaciones internas (campanita), SOLO en la app, sin correo:
   - Entidad Notification: id, destinatario (usuario), tipo (RIESGO_ESTUDIANTE, ALERTA_ASIGNADA, JUSTIFICACION_PENDIENTE, NUEVO_MENSAJE, etc.), studentId (referencia, NO el nombre copiado en la tabla), nivel de riesgo, motivo estructurado (códigos de factores + valores, ej. {promedio: 2.6, materia, faltas: 3}), leída, fecha de lectura, fecha de creación.
   - Al publicarse un análisis de riesgo o un evento de faltas/notas, se notifica en tiempo real (/user/queue/notifications) a los DOCENTES de las materias afectadas, además de consejero/bienestar según la alerta.
   - Lo que ve el docente: nombre del estudiante (resuelto en lectura, con auditoría y ABAC: solo estudiantes de sus grupos), nivel de riesgo y motivo legible generado por plantilla desde el motivo estructurado ("Bajó su promedio a 2.6 en Anatomía y tiene 3 faltas"). Solo motivos académicos: nunca psicológicos, de salud ni socioeconómicos.
   - Acciones en la notificación: "Abrir chat" (devuelve o crea el chat docente-estudiante) y "Copiar enlace de cita" (devuelve el enlace de agenda del docente).
   - Endpoints REST: listar paginado, contador de no leídas, marcar como leída una/todas. Deduplicación (no repetir la misma notificación del mismo estudiante si no cambió el nivel o el motivo) y expiración configurable.

3. Enlace de cita del docente:
   - Campo appointmentLink en el perfil del docente (su página de reservas de citas, ej. Google Calendar o Microsoft Bookings), editable solo por el propio docente.
   - Validación estricta: solo https y lista blanca de dominios configurable (calendar.google.com, outlook.office.com, etc.); se rechaza cualquier otro esquema o dominio.
   - El backend solo guarda y entrega el enlace; no se integra con la API de calendario.

4. Chat interno docente-estudiante (módulo chat):
   - Entidades: Conversation (id, docente, estudiante, grupo/materia de contexto opcional, fecha de creación, último mensaje), Message (id, conversación, emisor, tipo TEXTO | AUDIO | DOCUMENTO, contenido de texto cifrado, attachmentId, fecha, editado/eliminado lógicamente) y ChatAttachment (clave en MinIO, nombre original saneado, mime, tamaño, duración si es audio, sha256, estado del antivirus). Una sola conversación por par docente-estudiante; índice para paginar mensajes por keyset.
   - Reglas de acceso: un docente solo puede iniciar chat con estudiantes inscritos en sus grupos del periodo activo; el estudiante puede responder y ver sus conversaciones; nadie más lee el chat salvo ADMIN con motivo registrado en auditoría. Diseña Conversation con participantes para poder habilitar luego consejero/psicólogo sin migración grande.
   - Envío y recepción en tiempo real por STOMP (/app/chat.send, entrega en /user/queue/chat). El controlador valida participación, longitud y rate limit, persiste el mensaje, y luego lo entrega. Mensajes de solo lectura cuando el estudiante deja de estar inscrito.
   - Archivos: endpoint REST multipart para subir adjuntos ANTES de enviar el mensaje; responde con attachmentId (no una URL pública). Tipos permitidos por magic bytes: PDF, Word .docx (rechazar .doc y archivos con macros como .docm), audio (webm/opus, ogg, mp3, m4a/mp4) con tamaño y duración máximos configurables. Escaneo ClamAV obligatorio antes de que el adjunto se pueda enviar. Descarga solo mediante URL prefirmada de corta duración emitida tras verificar que quien pide es participante, con auditoría. Almacenamiento en MinIO/S3 alojado en Colombia.
   - Funciones: historial paginado, no leídos por conversación, confirmación de lectura, indicador "escribiendo" opcional, eliminar mensaje propio (borrado lógico, queda en auditoría), reportar mensaje inapropiado.
   - Privacidad: contenido cifrado en reposo, retención configurable, los mensajes y adjuntos NUNCA se envían a la IA ni aparecen en logs.
   - Endpoint para la vista dividida: GET /chats/{id}/student-context devuelve el perfil del estudiante (datos básicos permitidos para el docente, materias que comparte con él, faltas y notas en esas materias) y las series para las gráficas de riesgo (historial de nivel/score en el tiempo, faltas por semana, notas por materia), aplicando el mínimo necesario por rol.
5. Intervenciones: tipos (tutoría, psicología, beca, reunión con padres, flexibilización, ayuda económica, otro), estado PENDIENTE/EN_PROCESO/RESUELTA, responsable, fecha límite, resultado, notas de seguimiento con visibilidad por rol (las notas de psicología solo para PSICOLOGO). Historial por estudiante. Al resolver una intervención se dispara un nuevo análisis para medir el efecto, y se guarda el "antes/después" del riesgo.
6. Aprobación del plan de apoyo: endpoint para que un consejero revise el plan generado por la IA, lo edite, lo apruebe y lo convierta en intervenciones.
7. Retención (retention_case):
   - Apertura por detección automática (reglas/IA), por autorreporte del estudiante desde el portal ("estoy pensando en retirarme") o por un consejero.
   - Etapas DETECTADO → CONTACTADO → ENTREVISTA → PLAN_DE_APOYO → SEGUIMIENTO → cierre; registro de intentos de contacto, notas de entrevista (restringidas), plan con tareas y fechas, seguimiento programado a 7/15/30 días con recordatorios.
   - Cierre con resultado RETENIDO, APLAZADO/RESERVA_DE_CUPO, TRANSFERIDO o RETIRADO; si es RETIRADO, causa del catálogo y encuesta de salida; el estado del estudiante se actualiza con auditoría.
   - Campaña de reingreso para retirados/aplazados (tarea programada que crea recordatorios de contacto; el envío masivo exige aprobación).
   - Métricas del embudo (detectados, contactados, retenidos, retirados) por programa y periodo.

Criterios de aceptación: tests del ciclo completo (umbral de asistencia → alerta → consejero revisa plan → intervención → resolución → reanálisis); test de deduplicación y escalamiento; test de visibilidad de notas por rol; test de transiciones válidas/inválidas de etapa en retention_case.
Campanita y chat: test e2e con cliente STOMP (riesgo detectado → notificación en tiempo real al docente correcto → abrir chat → mensaje de texto y adjunto entregados al estudiante); test de que un docente sin relación con el estudiante no recibe la notificación ni puede abrir chat (403); test de que la notificación del docente no contiene factores psicológicos/socioeconómicos; test de que no se puede suscribir a la cola ni al chat de otro usuario; test de conexión STOMP sin token o con token vencido (rechazada); test de adjuntos (.docm, .doc, EICAR, extensión falsa → rechazados); test de enlace de cita con dominio no permitido o javascript: (rechazado); test de que ningún payload a la IA contiene texto del chat. Actualiza CLAUDE.md.
```

---

## FASE 6: Analítica, dashboard y reportes

```
Lee CLAUDE.md. Fase 6: módulos analytics, dashboard y reports.

1. Endpoints de analítica con un conjunto común de filtros (programId, semester, year/termId, from, to, riskLevel) para que el frontend pueda filtrar todos los gráficos a la vez al hacer clic en uno:
   - /analytics/dropout-by-program (barras)
   - /analytics/trend?granularity=month|year (líneas y área, tendencia histórica)
   - /analytics/by-semester (tortas)
   - /analytics/risk-distribution
   - /analytics/attendance-risk (materias con más faltas, estudiantes cerca del límite)
   - /analytics/retention-funnel
   Respeta ABAC: el coordinador solo ve su programa; las respuestas son agregados y aplican un tamaño mínimo de celda (suprimir grupos menores a N estudiantes para evitar reidentificación).
2. Rendimiento: vistas materializadas o tablas de resumen actualizadas de forma incremental, caché en Redis con invalidación por eventos, índices revisados con EXPLAIN. Objetivo: p95 < 500 ms con 50.000 estudiantes y 5 millones de filas de asistencia.
3. Dashboard (/dashboard/summary): totales de estudiantes, en riesgo, alertas abiertas, % de abandono, lista de ALTO/MEDIO/BAJO paginada, últimas notificaciones. Contenido según el rol.
4. Reportes asíncronos: POST /reports (general, por carrera/semestre/año, estudiantes en riesgo, asistencia) en PDF (OpenPDF) y Excel (Apache POI, con streaming SXSSF para archivos grandes); GET /reports/{id} para el estado y descarga con URL prefirmada y expiración. Los reportes con datos identificables solo para roles autorizados, con marca de agua/encabezado de confidencialidad y registro en auditoría.
5. Datos históricos: soporte para cargar series históricas de deserción de periodos anteriores (para la tendencia) con su plantilla de importación.

Criterios de aceptación: tests de cada endpoint con datasets sintéticos grandes, test de supresión de celdas pequeñas, test de ABAC, prueba de rendimiento documentada en el README (JMeter o Gatling), generación de un Excel de 100.000 filas sin agotar memoria. Actualiza CLAUDE.md.
```

---

## FASE 7: Portal del estudiante (API), hardening y salida a producción

```
Lee CLAUDE.md. Fase 7: portal del estudiante, endurecimiento y despliegue.

1. API del portal del estudiante (rol ESTUDIANTE, solo sus propios datos): materias inscritas, notas, contador de faltas por materia con la política aplicable, historial de faltas, justificar con evidencias y ver el estado de sus justificaciones, apelar, ver recomendaciones y recursos de apoyo (redactadas en lenguaje comprensivo, sin etiquetas alarmantes ni porcentajes de "probabilidad de abandono"), botón de "necesito ayuda / estoy pensando en retirarme" que abre un caso de retención, campanita propia (justificación aprobada/rechazada, nuevo mensaje), acceso a sus chats con docentes y al enlace de cita de cada docente, y gestión de consentimientos y solicitudes de acceso/rectificación/supresión de datos.
2. Cumplimiento: módulo de consentimiento y finalidad, política de retención de datos con jobs de depuración, flujo de solicitud de supresión con aprobación y trazabilidad, exportación de datos del titular, registro de actividades de tratamiento. Deja documentado qué debe validar el área jurídica (Ley 1581, datos sensibles, encargado del tratamiento, transmisión/transferencia internacional por el uso de la API de IA, registro de bases de datos ante la SIC).
3. Endurecimiento: revisión contra OWASP ASVS nivel 2, pruebas de autorización negativas en todos los endpoints (script que recorre el OpenAPI y verifica 401/403), límites de tamaño de petición, protección contra enumeración de usuarios, tests de inyección, dependencias fijadas y escaneadas, imagen Docker mínima sin root, SBOM.
4. Observabilidad y operación: dashboards de Grafana (latencia, errores, cola de eventos, costo y tokens de IA, correos enviados/rebotados, análisis por minuto), alertas operativas, health/readiness, trazas OpenTelemetry, política de backups cifrados con prueba de restauración documentada, runbook de incidentes (incluida la brecha de datos personales).
5. Rendimiento y escalabilidad: pruebas de carga (Gatling) con 50.000 estudiantes y picos de asistencia a la misma hora, ajuste de pools de conexiones, particionado verificado, estrategia de réplica de lectura para analítica, y documento de dimensionamiento.
6. Despliegue en nube con datos en Colombia (Oracle Cloud región Bogotá o proveedor local equivalente): PostgreSQL gestionado, almacenamiento de objetos para evidencias y adjuntos del chat, RabbitMQ con plugin STOMP, Redis, y backups, todo en la misma región. Balanceador con soporte de WebSocket (timeouts largos, sticky sessions no requeridas gracias al broker relay). Dockerfiles, manifiestos (Kubernetes/Helm o Compose de producción), pipeline CD con migraciones Flyway controladas, separación de ambientes, gestión de secretos con el vault del proveedor, y checklist de salida a piloto. Documenta el flujo de datos que sale del país (solo hacia la API de IA, seudonimizado) para la revisión jurídica.
7. Documentación final: README, guía de arquitectura (diagramas C4), catálogo de eventos, guía para conectar los datos reales, y manual de configuración de reglas de riesgo y de la política de asistencia.

Criterios de aceptación: suite de seguridad en verde en CI, pruebas de carga con resultados documentados, restauración de backup probada, checklist de piloto completa. Actualiza CLAUDE.md.
```

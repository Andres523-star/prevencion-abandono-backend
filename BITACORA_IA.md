# Bitácora de uso de IA

Este proyecto se desarrolla con asistencia de inteligencia artificial. Esta bitácora deja rastro
transparente de qué se generó con IA, con qué herramienta y bajo qué revisión humana.

- **Herramienta:** Claude Code (modelo Claude Opus 5.5, Anthropic).
- **Modo de trabajo:** el equipo entrega el alcance por fases (CLAUDE.md); la IA propone el diseño,
  escribe el código, las pruebas y la documentación, y ejecuta `./mvnw verify`. El equipo revisa y
  aprueba cada fase antes de continuar.
- **Marcas en el código:** cada `package-info.java` de módulo, `pom.xml`, la migración Flyway,
  `application.yml`, `Dockerfile`, `docker-compose.yml`, `render.yaml` y el workflow de CI llevan la
  nota *"Asistido por IA (Claude, Anthropic)"*. Los commits llevan la línea `Co-Authored-By: Claude`.
- **Datos:** la IA trabajó solo con el enunciado y datos ficticios. No se le entregaron datos reales
  de estudiantes ni secretos; los secretos de prueba se generan al azar en cada ejecución.

## Registro por fase

### Fase 1 — Fundaciones y seguridad base (2026-11-03)

**Generado por IA:**
- Proyecto Maven (Java 21, Spring Boot 3.5.16, Spring Modulith 1.4.14) con los módulos `common`,
  `auth`, `users`, `audit`, `settings`.
- Migración `V1__fundaciones.sql` (usuarios, roles, refresh tokens, auditoría particionada e
  inmutable, settings, outbox de eventos).
- Autenticación con Entra ID (OIDC + PKCE), sesión propia con refresh rotativo y detección de
  reutilización, MFA por rol, cuentas break-glass (argon2id + TOTP + bloqueo progresivo).
- RBAC + ABAC reutilizable, auditoría con hash encadenado, cifrado envolvente AES-256-GCM y blind index.
- Rate limiting (Bucket4j + Redis), cabeceras de seguridad, CORS estricto, ProblemDetail, logs JSON
  con enmascaramiento de PII, request-id, Micrometer/Prometheus, OpenAPI.
- Docker Compose, Dockerfile, Blueprint de Render, GitHub Actions (build, Trivy, gitleaks).
- 117 pruebas (54 unitarias/arquitectura + 63 de integración con PostgreSQL real e IdP simulado).
- Aplicación explícita de patrones: ver `docs/patrones-de-diseno.md`.

**Decisiones que requerían validación humana:** residencia en Colombia y claim `amr` de Entra; ambas quedaron sin efecto en la Fase 1.1.

**Revisión humana:** pendiente por el equipo.

### Fase 1.1 — Cambio de proveedor de identidad (2026-11-03)

Pedido del equipo: dejar de exigir alojamiento en Colombia, no depender de confirmación de TI y
permitir el ingreso con cualquier correo.

**Generado por IA:**
- Reemplazo de Microsoft Entra ID por **Supabase Auth**: canje `POST /api/v1/auth/supabase/exchange`,
  validación del JWT (JWKS ES256/RS256 o secreto HS256 heredado), anti-replay del token, consulta de
  `/auth/v1/user` para exigir correo confirmado, MFA por `aal2` y `SupabaseIdentityAdapter`.
- `APP_ALLOWED_EMAIL_DOMAINS` vacío = cualquier correo. Columnas genéricas `external_issuer` /
  `external_subject` (V1 editada antes de cualquier despliegue).
- `OneTimeTicketStore.claim` (SET NX) para el anti-replay; Supabase simulado con WireMock en las pruebas.
- Documentación: `docs/supabase-auth.md`, Render sin restricción de residencia.

**Recomendación de la IA aceptada por defecto:** exigir correo verificado (`APP_REQUIRE_VERIFIED_EMAIL=true`).

**Revisión humana:** pendiente por el equipo.

### Fase 2 — Estructura académica, usuarios y estudiantes (2026-11-03)

**Generado por IA:**
- Módulo `academic`: 6 programas sembrados, periodos, festivos de Colombia calculados (Ley
  Emiliani y Pascua), suspensiones de clase, materias con prerrequisitos (sin ciclos), grupos
  regulares y de práctica clínica, horarios, generación idempotente de sesiones y políticas de
  asistencia por programa / práctica clínica / materia administrables por API.
- Módulo `students`: `anon_code` aleatorio (Base32 Crockford, 50 bits), identidad cifrada con
  blind index de documento y correo, perfil socioeconómico restringido, inscripciones con cupo,
  consentimiento con historial y vínculo automático usuario-estudiante por correo.
- Gestión de usuarios: alta previa al primer ingreso, edición, listado por rol, borrado lógico y
  validación de programas del coordinador.
- Módulo `imports`: CSV/Excel, dry-run, cola con `SKIP LOCKED`, idempotencia, mapeo configurable,
  reporte sin datos personales y plantillas.
- Generador de datos sintéticos (Datafaker) de 50.000 estudiantes para el perfil dev.
- Migración `V2__estructura_academica_y_estudiantes.sql`.
- Pruebas: 74 unitarias/arquitectura + 94 de integración (incluye importación de 10.000 filas y
  pruebas de ABAC por rol).

**Revisión humana:** pendiente por el equipo.

### Fase 3 — Registro académico, asistencia, correos y justificaciones (2026-11-04)

**Generado por IA:**
- Módulo `records`: notas (0.0–5.0, un decimal) con indicadores de riesgo (promedios, materias
  bajo 3.0, tendencia), asistencia por sesión con ventana de edición configurable, tabla
  particionada por periodo, contador de faltas transaccional, eventos de umbral y pérdida,
  observaciones cifradas y catálogo de causas de deserción.
- Módulo `justifications`: justificación por falta con evidencias (magic bytes, tamaño,
  antivirus antes de guardar, nombres aleatorios, URL prefirmada auditada), decisión exclusiva
  del docente del grupo, traspaso al nuevo docente y vencimiento diario.
- Módulo `mailing`: outbox de avisos al estudiante (ventana de edición o resumen diario),
  revalidación al enviar, 8 plantillas Thymeleaf sin datos sensibles, reintentos con backoff,
  rebotes por webhook HMAC con supresión y adaptadores Microsoft Graph, Resend, log y memoria.
- Módulo `wellbeing`: evaluaciones y remisiones solo para PSICOLOGO, cifradas y con auditoría de
  lectura.
- Infraestructura `common.files` (almacenamiento local/S3, ClamAV, detección por magic bytes) y
  externalización opcional de eventos a RabbitMQ.
- Importación de notas (`GRADES`), migración `V3__registro_academico_justificaciones_y_correo.sql`,
  servicio privado ClamAV en `render.yaml` y variables nuevas en `.env.example`.
- Pruebas: 99 unitarias/arquitectura + 109 de integración, incluido el flujo de punta a punta
  (falta → correo encolado → justificación con evidencia → antivirus → decisión → contador →
  evento), 403 para todo el que no sea el docente del grupo, EICAR y extensión engañosa
  rechazados, correo sin datos sensibles y prueba de volumen con 5.063.823 filas
  (`docs/rendimiento.md`).

**Revisión humana:** pendiente por el equipo (en especial el texto de las plantillas de correo
y la elección del proveedor de correo).

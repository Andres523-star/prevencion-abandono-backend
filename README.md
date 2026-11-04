# Backend — Plataforma de retención estudiantil

Backend en **Java 21 + Spring Boot 3.5** (monolito modular con Spring Modulith) para detectar,
predecir y prevenir la deserción estudiantil. Estado actual: **Fase 2 — estructura académica, usuarios y estudiantes** (sobre la Fase 1 de seguridad base).

> Proyecto desarrollado con asistencia de IA (Claude, Anthropic). Ver [BITACORA_IA.md](BITACORA_IA.md).

## Inicio rápido

Requisitos: JDK 21, Docker (opcional para pruebas: sin Docker se usa PostgreSQL embebido).

```bash
cp .env.example .env          # completa contraseñas y claves (openssl rand -base64 32)
docker compose up -d          # PostgreSQL 16, Redis, RabbitMQ, MinIO, ClamAV
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

- Swagger UI (dev): http://localhost:8080/swagger-ui.html
- Salud: http://localhost:8080/actuator/health

Pruebas completas (unitarias + integración + arquitectura + cobertura):

```bash
./mvnw verify
```

## Módulos (Spring Modulith)

| Módulo | Responsabilidad | API pública |
|---|---|---|
| `common` (abierto) | IDs UUID v7, errores RFC 7807, JWT propio, RBAC/ABAC, cifrado AES-256-GCM, blind index, rate limit, request-id | todo |
| `auth` | Login con Supabase Auth (cualquier correo verificado), sesión propia con refresh rotativo, MFA, break-glass | solo REST |
| `users` | Usuarios internos, roles, alcance por programa | `UserDirectory`, `UserAccount`, `UserProvisioned` |
| `audit` | Bitácora append-only particionada con hash encadenado | `@Audited`, `@SensitiveRead`, `AuditTrail` |
| `settings` | Parámetros en caliente (sin redeploy) | `SettingsProvider`, `SettingChanged` |
| `academic` | Programas, periodos, festivos, materias, grupos, horarios, sesiones y políticas de asistencia | `AcademicDirectory`, `AcademicCatalog`, `GroupTeacherChanged` |
| `students` | Estudiante seudonimizado, identidad cifrada, perfil socioeconómico, inscripciones, consentimiento | `StudentRegistry`, `StudentInfo` |
| `imports` | Importación CSV/Excel con dry-run, cola y reporte (incluye notas) | solo REST |
| `records` | Notas e indicadores de riesgo, asistencia por sesión (particionada por periodo), contador de faltas, observaciones y causas de deserción | `AttendanceLedger`, `GradeBook`, `AbsenceRecorded`, `AttendanceThresholdReached`, `SubjectLostByAbsences` |
| `justifications` | Justificaciones con evidencias (magic bytes + antivirus), decisión exclusiva del docente, vencimiento | `JustificationDecided` |
| `mailing` | Avisos de faltas al estudiante (outbox, ventana de edición o resumen diario, reintentos, rebotes) | solo eventos |
| `wellbeing` | Evaluaciones físicas/psicológicas y remisiones (solo PSICOLOGO, cifrado, auditoría de lectura) | `WellbeingSignals` |

Capas por módulo: `api` → `application` → `domain` ← `infrastructure`. Límites verificados por
`ModularityTest` (Modulith) y `ArchitectureRulesTest` (ArchUnit).

## Endpoints principales

| Método y ruta | Acceso | Descripción |
|---|---|---|
| `POST /api/v1/auth/supabase/exchange` | público | Canjea el token de Supabase por la sesión de la plataforma |
| `POST /api/v1/auth/refresh` | público | Rota el refresh token (reutilizarlo revoca la sesión) |
| `POST /api/v1/auth/logout` | autenticado | Cierra la sesión |
| `POST /api/v1/auth/break-glass/login` | público | Login de emergencia ADMIN (contraseña + TOTP) |
| `GET /api/v1/users/me` | autenticado | Perfil propio |
| `GET /api/v1/users` | ADMIN | Listado keyset sin PII |
| `GET /api/v1/users/{id}` | ABAC + `X-Access-Reason` | Detalle con identidad (auditado) |
| `PUT /api/v1/users/{id}/roles` | ADMIN | Roles y programas |
| `PUT /api/v1/users/{id}/status` | ADMIN | Activar / desactivar |
| `GET/PUT /api/v1/settings[/{key}]` | ADMIN | Parámetros configurables |
| `GET /api/v1/audit/verify` | ADMIN | Verifica la cadena de hashes |
| `POST/PATCH/DELETE /api/v1/users[/{id}]` | ADMIN | Alta previa al primer ingreso, edición y borrado lógico |
| `GET /api/v1/programs`, `/terms`, `/holidays` | autenticado | Catálogo y calendario (`POST /holidays/colombia/{año}` carga festivos) |
| `POST /api/v1/subjects`, `/groups` | ADMIN, COORDINADOR (su programa) | Materias con prerrequisitos y grupos con horario |
| `POST /api/v1/groups/{id}/sessions/generate` | ADMIN, COORDINADOR | Genera sesiones (sin festivos ni suspensiones) |
| `PATCH /api/v1/sessions/{id}` | docente del grupo, COORDINADOR, ADMIN | Dictada / cancelada / reprogramada |
| `GET/PUT /api/v1/{programs,subjects}/{id}/attendance-policy` | ADMIN, COORDINADOR | Políticas de asistencia (materia > práctica clínica > programa) |
| `POST /api/v1/students`, `GET /students` | ADMIN, COORDINADOR / personal según alcance | Alta y listado seudonimizado |
| `GET /api/v1/students/{id}/identity` | ADMIN, CONSEJERO, PSICOLOGO, COORDINADOR + `X-Access-Reason` | Identidad (auditada) |
| `GET /api/v1/students/me` | ESTUDIANTE | Portal: mis datos |
| `GET /api/v1/groups/{id}/roster` | docente del grupo y personal | Lista de clase (auditada) |
| `POST /api/v1/imports` | ADMIN | Importación masiva (ver docs/importacion.md) |
| `PUT /api/v1/sessions/{id}/attendance` | docente del grupo (48 h); COORDINADOR/ADMIN después, con `X-Access-Reason` | Asistencia individual o por lote |
| `GET /api/v1/students/{id}/absence-counters`, `/groups/{id}/absence-counters` | según alcance | Contadores de faltas por materia |
| `POST /api/v1/groups/{id}/grades`, `/enrollments/{id}/grades` | docente del grupo, COORDINADOR, ADMIN | Notas (lote o individual) |
| `GET /api/v1/students/{id}/grade-indicators` | según alcance | Promedios, materias bajo 3.0 y tendencia |
| `POST /api/v1/justifications`, `/justifications/{id}/evidences` | ESTUDIANTE titular | Justificar una falta y adjuntar PDF/JPG/PNG |
| `POST /api/v1/justifications/{id}/decision` | solo el docente del grupo | Aprobar o rechazar (comentario obligatorio al rechazar) |
| `GET /api/v1/evidences/{id}/download-url` | titular, docente asignado, ADMIN | URL prefirmada de 5 min (auditada) |
| `GET /api/v1/mail/outbox`, `POST /api/v1/mail/bounces` | ADMIN / webhook firmado | Registro de entrega y rebotes |
| `/api/v1/students/{id}/wellbeing/*` | solo PSICOLOGO + `X-Access-Reason` | Evaluaciones y remisiones |
| `/api/v1/students/{id}/observations`, `/causes`, `/dropout-causes` | personal según rol | Observaciones y causas de deserción |

Errores siempre en `application/problem+json` con campo `code` estable.

## Documentación

- [Patrones de diseño aplicados](docs/patrones-de-diseno.md) (5 creacionales + Proxy, Decorator, Adapter)
- [Inicio de sesión con Supabase Auth](docs/supabase-auth.md)
- [Despliegue en Render](docs/render-despliegue.md)
- [Cuenta de emergencia (break-glass)](docs/break-glass.md)
- [Importación masiva y datos sintéticos](docs/importacion.md)
- [Asistencia, justificaciones y correo de avisos](docs/asistencia-y-correo.md)
- [Rendimiento (asistencia a 5 millones de filas)](docs/rendimiento.md)
- Diagramas de módulos (PlantUML): `target/spring-modulith-docs/` tras `./mvnw verify`

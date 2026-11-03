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

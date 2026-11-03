# Backend — Plataforma de retención estudiantil

Backend en **Java 21 + Spring Boot 3.5** (monolito modular con Spring Modulith) para detectar,
predecir y prevenir la deserción estudiantil. Estado actual: **Fase 1 — fundaciones y seguridad base**.

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

Capas por módulo: `api` → `application` → `domain` ← `infrastructure`. Límites verificados por
`ModularityTest` (Modulith) y `ArchitectureRulesTest` (ArchUnit).

## Endpoints de la Fase 1

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

Errores siempre en `application/problem+json` con campo `code` estable.

## Documentación

- [Patrones de diseño aplicados](docs/patrones-de-diseno.md) (5 creacionales + Proxy, Decorator, Adapter)
- [Inicio de sesión con Supabase Auth](docs/supabase-auth.md)
- [Despliegue en Render](docs/render-despliegue.md)
- [Cuenta de emergencia (break-glass)](docs/break-glass.md)
- Diagramas de módulos (PlantUML): `target/spring-modulith-docs/` tras `./mvnw verify`

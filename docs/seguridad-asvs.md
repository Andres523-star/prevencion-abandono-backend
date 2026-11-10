# Revisión contra OWASP ASVS 4.0 nivel 2

Resumen por capítulo con la evidencia en el código y las pruebas. "Pendiente" son brechas conocidas.

| Capítulo | Cómo se cumple | Evidencia |
|---|---|---|
| V1 Arquitectura | Monolito modular con límites verificados; identidad separada y cifrada; defensa en profundidad (RBAC + ABAC + auditoría) | `ModularityTest`, `ArchitectureRulesTest`, `docs/arquitectura.md` |
| V2 Autenticación | Supabase Auth con correo verificado y MFA TOTP (`aal2`) obligatorio para roles privilegiados; break-glass con argon2id + TOTP + bloqueo progresivo | `SupabaseLoginIT`, `BreakGlassIT` |
| V3 Sesiones | JWT propio de 15 min + refresh opaco rotativo con detección de reutilización; logout revoca la familia y cierra WebSockets | `RefreshTokenIT`, `RealtimeIT` |
| V4 Control de acceso | `@PreAuthorize` por rol + ABAC por programa, grupo y titular; falla cerrado; recursos fuera de alcance responden 404 | `SecurityMatrixIT` (todos los endpoints sin token → 401; sin rol / estudiante → nunca 2xx fuera de lo permitido), `RoleAuthorizationIT`, `HardeningIT` |
| V5 Validación y codificación | Bean Validation, `fail-on-unknown-properties`, enums y UUID tipados, SQL siempre parametrizado (JPA o `NamedParameterJdbcTemplate`); los textos se guardan como dato (escaparlos es tarea de la interfaz); Excel sin fórmulas | `HardeningIT.injectionAttemptsAreTreatedAsData`, `ReportWritersTest` |
| V6 Criptografía | AES-256-GCM con envelope encryption (KMS local o Vault Transit), blind index HMAC-SHA256, argon2id, claves solo por entorno | `CryptoTest` |
| V7 Errores y logs | RFC 7807 sin trazas ni mensajes internos; logs JSON con enmascarado de PII; auditoría append-only encadenada | `AuditLogImmutabilityIT`, `AuditChainIT` |
| V8 Protección de datos | Mínimo necesario por rol, supresión de celdas en analítica, IA solo con datos seudonimizados, retención configurable, derechos del titular | `AiDomainTest`, `AnalyticsIT`, `PrivacyIT` |
| V9 Comunicaciones | TLS en Render, HSTS, IP real por `X-Forwarded-*` solo desde proxies de confianza | `SecurityHeadersIT` |
| V10 Código malicioso | Dependencias fijadas (maven-enforcer: sin SNAPSHOT ni rangos), SBOM CycloneDX, Trivy de dependencias, SBOM e imagen, gitleaks | `.github/workflows/ci.yml`, `pom.xml` |
| V11 Lógica de negocio | Transiciones explícitas (justificaciones, casos, solicitudes), cuatro ojos en la supresión, solo el docente decide justificaciones, límites por usuario | `AbsenceFlowIT`, `RetentionIT`, `PrivacyIT` |
| V12 Archivos | *Magic bytes* + extensión coherente + tamaño + antivirus antes de guardar; nombres aleatorios; descarga solo por URL prefirmada corta | `AbsenceFlowIT`, `ChatIT` |
| V13 API | Límite del cuerpo JSON (1 MB, 413), rate limit por IP/usuario, CORS sin comodines, sin enumeración por respuestas distintas | `HardeningIT`, `RateLimitIT`, `BreakGlassIT.unknownUserGetsSameGenericError` |
| V14 Configuración | Imagen JRE Alpine sin root (uid 10001), secretos por entorno, actuator restringido a ADMIN salvo health, OpenAPI apagado en prod | `Dockerfile`, `application-prod.yml` |

## Pendientes

- Revocación inmediata del access token tras logout para la API REST (hoy vive hasta 15 min; los WebSockets sí se
  cierran al instante).
- La cadena de auditoría es global y serializa escrituras: se ve como contención en el pico de asistencia
  (`docs/rendimiento.md`). Pasar a cadenas por partición si el volumen lo exige.
- Prueba de penetración externa y revisión del frontend (CSP, almacenamiento de tokens) antes del piloto.
- Rotación programada de claves con el KMS de la nube final.

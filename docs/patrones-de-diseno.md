# Patrones de diseño aplicados (Fases 1 a 4)

Requisito del proyecto: aplicar los **5 patrones creacionales** y, de los estructurales,
**Proxy, Decorator y Adapter**. Cada clase lleva en su Javadoc el patrón que implementa y por qué.

## Creacionales

| Patrón | Clase | Para qué se usa |
|---|---|---|
| **Singleton** | `common.id.UuidV7` | Generador único de UUID v7 por proceso: guarda el último milisegundo y un contador para que los ids sean estrictamente crecientes. Implementado como `enum` (forma recomendada en Java). |
| **Factory Method** | `common.crypto.KmsProviderFactory` (`Local`, `Vault`) | Cada fábrica concreta decide qué `KmsProvider` construir según `app.crypto.kms-type`. Agregar OCI Vault o AWS KMS = nueva fábrica, sin tocar `CryptoConfig`. |
| **Factory Method** | `mailing.infrastructure.MailInfrastructureConfig.emailSender` | Construye el `EmailSender` según `app.mail.provider` (Graph, Resend, log, memoria) y valida su configuración al arrancar. |
| **Factory Method** | `common.files.FilesConfig` | Elige el almacenamiento (`local` o `s3`) y el antivirus (`clamav` o `signature`) por configuración. |
| **Factory Method** | `imports.infrastructure.TableReader.forFormat` | Decide qué lector construir (CSV o Excel) según el formato detectado por *magic bytes*. |
| **Abstract Factory** | `common.state.DistributedStateFactory` (`InMemoryStateFactory`, `RedisStateFactory`) | Crea una *familia* coherente de componentes de estado compartido (rate limiter, almacén de tickets de un solo uso y anti-replay, y caché): todos en memoria (dev/test) o todos en Redis (producción con varias instancias). |
| **Factory Method** | `reports.infrastructure.ReportWriterFactory`, `reports.application.ReportContentFactory` | Eligen el escritor según el formato (PDF o Excel) y el contenido según el tipo de reporte. |
| **Factory Method** | `risk.application.ScorerFactory` | Decide en cada análisis qué `RiskScorer` construir: `MlScorer` si `risk.ml.active-version` apunta a un modelo APROBADO y cargable, `RulesScorer` en cualquier otro caso. Activar o volver atrás un modelo es cambiar un parámetro. |
| **Builder** | `ai.domain.AiPayload.Builder` | Compone el payload hacia la IA por partes opcionales (señales sensibles, texto libre redactado, plan, historial, recursos) en orden canónico; `build()` valida contra la lista blanca, así no existe un payload sin validar. |
| **Builder** | `reports.domain.ReportDocument.Builder` | Arma un reporte por partes (título, aviso, marca de agua, secciones); `build()` exige el aviso de confidencialidad, así no existe un reporte sin él. |
| **Builder** | `audit.domain.AuditEntry.Builder` | Una entrada de auditoría tiene muchos campos opcionales (actor, IP, motivo, entidad); el builder evita constructores con una docena de parámetros y valida los obligatorios. |
| **Prototype** | `common.error.ApiProblem` + `common.error.ProblemCatalog` | El catálogo guarda una plantilla de error por código (tipo, título, estado HTTP); cada error clona la plantilla (`copy()`) y solo personaliza el detalle. Los módulos aportan sus prototipos con `ProblemCatalogContributor`. |

| **Prototype** | `risk.domain.SupportPlanTemplates` | Un plan de apoyo prototipo por nivel de asistencia (aviso, crítico, pérdida); cada plan nuevo es una copia (`PlanTemplate.copy()`) a la que se agregan acciones según el contexto, sin tocar el prototipo. |

## Estructurales

| Patrón | Clase | Para qué se usa |
|---|---|---|
| **Proxy** | `common.crypto.CachingKmsProviderProxy` | Misma interfaz que el KMS real; reutiliza la clave de datos y cachea las ya desenvueltas para no llamar a Vault en cada campo cifrado. |
| **Proxy** | `settings.infrastructure.CachingSettingsProxy` | Misma interfaz que `JpaSettingsProvider`; evita una consulta SQL por cada lectura de parámetro y se invalida con el evento `SettingChanged`. |
| **Proxy** (AOP) | `common.security.authorization.RequireScopeAspect`, `audit.application.AuditAspect` | Spring envuelve los beans en proxies; los aspectos deciden si la llamada llega al objeto real (ABAC) y la registran (auditoría). |
| **Decorator** | `audit.infrastructure.PiiScrubbingAuditSinkDecorator`, `MeteredAuditSinkDecorator` | Se apilan sobre `JdbcAuditEventSink` para depurar PII y medir, sin modificar la escritura base: `métricas → depuración PII → JDBC encadenado`. |
| **Adapter** | `auth.infrastructure.supabase.SupabaseIdentityAdapter` | Traduce el token y el usuario de Supabase Auth (`sub`, `email`, `aal`, `user_metadata`, `email_confirmed_at`) al modelo interno `FederatedIdentity`. Cambiar de proveedor = otro adaptador. |
| **Adapter** | `common.crypto.VaultTransitKmsAdapter` | Adapta la API HTTP de Vault Transit a la interfaz `KmsProvider`. |
| **Adapter** | `common.ratelimit.Bucket4jLocalRateLimiter`, `Bucket4jRedisRateLimiter`, `RedisStateFactory.RedisTicketStore` | Adaptan Bucket4j y los comandos Redis (`SET PX` / `GETDEL` / `SET NX`) a nuestros puertos `RateLimiter` y `OneTimeTicketStore`. |
| **Adapter** | `imports.infrastructure.CsvTableReader`, `XlsxTableReader` | Adaptan Apache Commons CSV y Apache POI a un único puerto `TableReader`. |
| **Adapter** | `common.files.S3ObjectStorage`, `LocalObjectStorage` | Adaptan el cliente MinIO (S3, Supabase Storage, R2) y el disco local al puerto `ObjectStorage`, con URL prefirmada de corta duración. |
| **Adapter** | `common.files.ClamAvScanner` | Adapta el protocolo `zINSTREAM` de clamd al puerto `MalwareScanner`. |
| **Adapter** | `mailing.infrastructure.GraphEmailSender`, `ResendEmailSender` | Adaptan Microsoft Graph `sendMail` (client credentials) y la API de Resend al puerto `EmailSender`. |

| **Adapter** | `ai.infrastructure.AnthropicLlmGateway` | Adapta el SDK oficial de Anthropic al puerto `LlmGateway` y traduce sus excepciones (429, 5xx, red, timeout) a un motivo normalizado (`AiFailure`). |
| **Adapter** | `risk.infrastructure.OnnxModelRuntime` | Adapta ONNX Runtime al puerto `MlModelRuntime` y al `ProbabilityModel` del dominio (tensor `[N,2]`, `[N,1]` o ZipMap). |
| **Decorator** | `ai.infrastructure.BudgetedLlmGateway`, `ResilientLlmGateway` | Se apilan sobre el adaptador de Claude: `presupuesto diario de tokens → bulkhead + rate limiter + circuit breaker → SDK`. |
| **Adapter** | `reports.infrastructure.XlsxReportWriter`, `PdfReportWriter` | Adaptan Apache POI (SXSSF) y OpenPDF al puerto `ReportWriter`. |
| **Adapter** | `realtime.infrastructure.StompRealtimePublisher` | Adapta `SimpMessagingTemplate` (broker simple o relay sobre RabbitMQ) al puerto `RealtimePublisher`; alertas, campanita y chat no conocen STOMP. |
| **Decorator** | `realtime.infrastructure.RealtimeSessions` | Envuelve el manejador WebSocket de Spring (`WebSocketHandlerDecoratorFactory`) para registrar cada conexión y poder cerrarla al vencer el token o al revocar la sesión, sin tocar el manejador original. |

## Complementarios (no exigidos)

- **Strategy:** `risk.domain.RiskRule` (una regla por aspecto: promedio, faltas, tendencia, conducta, bienestar, causas, contexto socioeconómico), `imports.application.RowHandler` (una estrategia por tipo de importación, incluida `GradeRowHandler`) y `common.security.authorization.ScopeRules` (una regla ABAC por rol).
- **Strategy:** `realtime.SubscriptionAuthorizer` (un autorizador por familia de destinos STOMP; el del chat es `ChatSubscriptionAuthorizer`).
- **State:** `retention.domain.RetentionCase` y `interventions.domain.Intervention` (las transiciones válidas viven en la entidad; una transición inválida es 409).
- **Template Method / Observer:** eventos de dominio de Spring Modulith (`UserProvisioned` → `StudentUserLinker`, `SettingChanged` → `CachingSettingsProxy`).

## Pruebas que los cubren

- `CreationalPatternsTest`: Singleton (monotonía y unicidad), Prototype (copias independientes), Abstract Factory (familia en memoria).
- `CryptoTest`: Factory Method, Proxy del KMS (cuenta llamadas al KMS real).
- `AuditDomainTest`: Builder y cadena de Decorators.
- `InfrastructureUnitTest`: Proxy de settings.
- `RedisStateFactoryIT`: familia Redis de la Abstract Factory (requiere Docker).
- `SupabaseLoginIT` y `AuthDomainTest`: Adapter de Supabase, de punta a punta y unitario.
- `ImportUnitTest`: Factory Method y Adapters de lectura CSV/Excel.
- `FilesUnitTest`: Adapter de clamd (servidor clamd simulado) y del almacenamiento local firmado.
- `AbsenceMailIT` y `AbsenceFlowIT`: Adapter de correo en memoria y Factory Method del proveedor.
- `AiDomainTest`: Builder del payload (lista blanca, sin PII, hash estable).
- `RiskDomainTest`: Strategy de reglas, Prototype de planes y scorers.
- `OnnxModelRuntimeTest`: Adapter de ONNX Runtime con un modelo real construido en la prueba.
- `RiskEngineIT` y `MlModelIT`: Adapter + Decorators de Claude (WireMock) y Factory Method del scorer.
- `ReportWritersTest`: Factory Method y Adapters de reportes; `ReportDomainTest`: Builder del reporte.
- `RealtimeIT`: Adapter del publicador STOMP y Decorator de sesiones (cierre por vencimiento y logout) con un cliente STOMP real.

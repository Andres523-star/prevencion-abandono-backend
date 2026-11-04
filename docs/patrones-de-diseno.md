# Patrones de diseño aplicados (Fases 1 a 3)

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
| **Abstract Factory** | `common.state.DistributedStateFactory` (`InMemoryStateFactory`, `RedisStateFactory`) | Crea una *familia* coherente de componentes de estado compartido (rate limiter + almacén de tickets de un solo uso y anti-replay): todos en memoria (dev/test) o todos en Redis (producción con varias instancias). |
| **Builder** | `audit.domain.AuditEntry.Builder` | Una entrada de auditoría tiene muchos campos opcionales (actor, IP, motivo, entidad); el builder evita constructores con una docena de parámetros y valida los obligatorios. |
| **Prototype** | `common.error.ApiProblem` + `common.error.ProblemCatalog` | El catálogo guarda una plantilla de error por código (tipo, título, estado HTTP); cada error clona la plantilla (`copy()`) y solo personaliza el detalle. Los módulos aportan sus prototipos con `ProblemCatalogContributor`. |

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


## Complementarios (no exigidos)

- **Strategy:** `imports.application.RowHandler` (una estrategia por tipo de importación, incluida `GradeRowHandler`) y `common.security.authorization.ScopeRules` (una regla ABAC por rol).
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

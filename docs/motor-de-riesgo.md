# Motor de riesgo e IA (Fase 4)

Asistido por IA (Claude, Anthropic). Ver `BITACORA_IA.md`.

## Diseño híbrido en tres capas

| Capa | Interfaz | Implementación | Qué produce |
|---|---|---|---|
| Puntaje | `risk.domain.RiskScorer` | `RulesScorer` (hoy) y `MlScorer` (modelo ONNX propio) | `score`, `scoreType`, nivel y factores |
| Explicación | `ai.RiskExplainer` | `ClaudeRiskExplainer` | resumen, factores con peso, causas probables, recomendaciones con recursos del catálogo, `confidence` |
| Orquestación | — | `risk.application.RiskOrchestrator` | decide cuándo llamar a cada capa y guarda el análisis |

- `scoreType = RULES`: puntaje 0-100 sumado por reglas. **No es una probabilidad** y la API lo dice
  (`scoreMeaning`); el campo `probability` va en `null`.
- `scoreType = ML_CALIBRATED`: probabilidad calibrada del modelo propio. Es el único caso en que se
  muestra `probability`.
- `source`: `RULES` (solo el motor; la IA no se pidió o falló), `HYBRID` (nivel del motor + explicación
  de la IA) o `AI` (la IA subió el nivel; solo con `risk.ai-can-escalate = true`, apagado por defecto).

## Cuándo se llama a la IA (orquestador)

| Origen | ¿IA? | Modelo |
|---|---|---|
| Manual (`POST /students/{id}/analyze`) | siempre | capaz (`ai.model.default`) si el nivel es MEDIO o ALTO; económico (`ai.model.triage`) si es BAJO |
| Umbral de asistencia CRÍTICO o PÉRDIDA | siempre | capaz |
| Evento (nota, falta, justificación) y lote nocturno | si el nivel ≥ `ai.min-level` (MEDIO) | económico; capaz si el nivel es ALTO |

Fallback a reglas (el análisis se guarda igual, con `fallbackReason`): `DISABLED` (`ai.enabled=false`),
`NOT_CONFIGURED` (sin `ANTHROPIC_API_KEY`), `BUDGET_EXCEEDED`, `CIRCUIT_OPEN`, `RATE_LIMITED`, `TIMEOUT`,
`UNAVAILABLE`, `INVALID_OUTPUT` (JSON inválido o recurso inventado tras 1 reintento), `REFUSAL`,
`POLICY_VIOLATION` (payload rechazado por la lista blanca), `BELOW_MIN_LEVEL` (no se pidió).

Ninguna llamada a la IA ocurre dentro de una transacción de base de datos ni dentro de la petición del
docente: los eventos solo encolan (`risk_work_queue`) y `RiskWorker` procesa la cola.

## Reglas (configurables sin redeploy)

Cada regla es un objeto JSON en `app_settings` (`PUT /api/v1/settings/{clave}`, solo ADMIN). La caché
de settings se invalida al confirmar el cambio; el siguiente análisis ya usa los valores nuevos y
guarda la versión de las reglas (`rulesVersion`, hash de la configuración).

| Clave | Factor | Categoría | Por defecto |
|---|---|---|---|
| `risk.rules.low-average` | `LOW_AVERAGE` | ACADEMICO | < 3.0 → 15 puntos; < 2.5 → 25 |
| `risk.rules.failing-subjects` | `FAILING_SUBJECTS` | ACADEMICO | 10 por materia < 3.0 (máx. 30) |
| `risk.rules.absences` | `ABSENCES` | ASISTENCIA | por materia según su política: aviso 8, crítico 15, pérdida 25 (máx. 40) |
| `risk.rules.tardies` | `TARDIES` | ASISTENCIA | ≥ 3 tardanzas → 5 |
| `risk.rules.grade-trend` | `GRADE_TREND` | ACADEMICO | 5 por materia con tendencia BAJA (máx. 15) |
| `risk.rules.conduct` | `CONDUCT` | CONDUCTA | 4 por observación de conducta (máx. 12) |
| `risk.rules.wellbeing` | `WELLBEING` | BIENESTAR | 8 por remisión abierta (máx. 10) |
| `risk.rules.causes` | `OPEN_CAUSES` / `HEALTH_CAUSES` | CAUSAS / BIENESTAR | puntos por categoría (máx. 20) |
| `risk.rules.socioeconomic-context` | `SOCIOECONOMIC_CONTEXT` | SOCIOECONOMICO | 4 por condición, máx. 8, **solo con una señal académica o de asistencia** |
| `risk.levels` | — | — | MEDIO desde 30, ALTO desde 60 |

### Decisión de sesgo: el estrato no castiga

- Ninguna regla lee el estrato; no existe parámetro que lo haga sumar (si alguien agrega
  `stratumPoints` al JSON, se ignora). Prueba: `RiskDomainTest.stratumNeverChangesTheScore`.
- El contexto socioeconómico (trabajo, transporte, personas a cargo) solo aporta si ya hay una señal
  académica o de asistencia, con un tope bajo, y su uso principal es elegir el apoyo (auxilio de
  transporte, flexibilización de horario). Prueba: `socioeconomicContextOnlyCountsWithAnAcademicOrAttendanceSignal`.
- El estrato solo se usa en el backend para la **elegibilidad** de recursos (p. ej. becas para estratos
  1-3) y para las **métricas de sesgo**. Nunca sale hacia la IA ni entra al modelo ML.
- `GET /api/v1/risk/bias-metrics`: distribución del último nivel por programa, banda de estrato y
  semestre, con celdas de menos de 10 estudiantes suprimidas y la brecha máxima de "% en ALTO" entre
  bandas de estrato.

## Privacidad del payload hacia la IA

- **Lista blanca** (`ai.domain.PayloadWhitelist`): solo esas rutas salen; cualquier campo nuevo bloquea
  el envío (`POLICY_VIOLATION`). Los textos se revisan además con el detector de PII.
- El payload lleva `anon_code`, programa, semestre, métricas y bandas (promedio, personas a cargo),
  factores del motor y los recursos elegibles. Nunca nombre, documento, correo, teléfono ni estrato.
- **Texto libre** (observaciones): solo si `ai.free-text.enabled = true` (por defecto `false`), siempre
  redactado por `PiiRedactor` (regex de correos, URL, usuarios, teléfonos y documentos + diccionario de
  nombres `ai/nombres-comunes.txt`).
- **Señales sensibles** (bienestar, causas de salud): solo si `ai.payload.include-sensitive-signals =
  true` (por defecto `false`, requiere consentimiento expreso). Con ellas, el texto de la IA solo lo ve
  PSICOLOGO.
- La salida de la IA también se revisa: si trae PII, cita un recurso que no estaba en el payload o usa
  marcadores distintos de `{{nombre}}`/`{{materia}}`, se rechaza.

## Mínimo necesario por rol al leer análisis

| Rol | Factores | Texto de la IA |
|---|---|---|
| DOCENTE (solo sus grupos) | ACADEMICO, ASISTENCIA | nunca |
| COORDINADOR (su programa) | + CONDUCTA, CAUSAS | sí, salvo contexto sensible |
| CONSEJERO, ADMIN | todo menos BIENESTAR | sí, salvo contexto sensible |
| PSICOLOGO | todo | sí (incluidas causas de salud) |
| ESTUDIANTE | — (403; su portal llega en la Fase 7) | — |

## Cliente Claude

- SDK oficial `com.anthropic:anthropic-java`. Modelos por propiedad: `ai.model.default`
  (`claude-opus-5-5`) y `ai.model.triage` (`claude-haiku-5-5`); esfuerzo `medium` / `low`.
- Salida estructurada (`output_config.format` con JSON Schema) y validación local con el esquema
  estricto (`ai/schemas/*.json`, networknt). Reintento propio ante salida inválida (`ai.invalid-output-retries`).
- Cadena de decoradores: presupuesto diario (`ai.daily-token-budget`) → bulkhead → rate limiter →
  circuit breaker (Resilience4j, instancia `anthropic`) → adaptador del SDK (reintentos del SDK ante
  429/5xx con `retry-after`).
- Con el modelo capaz se pide el *fallback* del servidor ante rechazos (`fallbacks: "default"`, beta
  `server-side-fallback-2026-07-01`); un `stop_reason = refusal` termina en fallback a reglas.
- Cada análisis guarda `aiModel` (el que respondió), `promptVersion`, `payloadHash` y tokens; cada
  llamada queda en `ai_calls` (sin contenido) y el consumo diario en `ai_token_usage`.

## Base de conocimiento (RAG simple)

`support_resources` (11 recursos sembrados, CRUD en `/api/v1/support-resources`, solo ADMIN edita).
La recuperación es un filtro determinista por elegibilidad (programa, semestre, estrato, transporte,
trabajo) hecho en el backend; la IA recibe la lista filtrada y solo puede citar esos ids.

## Planes de apoyo por asistencia

`AttendanceThresholdReached` / `SubjectLostByAbsences` → vigilancia + análisis inmediato + plan.
Desde `ai.support-plan.min-level` (CRÍTICO) el plan lo redacta la IA con el modelo capaz; antes, o si
falla, se usa la plantilla por nivel (`SupportPlanTemplates`, patrón Prototype). El plan queda
`PROPUESTO` hasta que CONSEJERO, COORDINADOR o PSICOLOGO lo aprueba o lo rechaza (con comentario). El
texto al estudiante usa `{{nombre}}` y `{{materia}}`; el nombre solo se rellena en
`GET /support-plans/{id}/rendered`, que exige `X-Access-Reason` y queda auditado.

## Análisis por lotes

`nightlyRiskAnalysis` (Spring Batch, 01:30 Bogotá): una partición por programa, lector keyset de
estudiantes activos, filtro por hash del payload (solo cambiados o en vigilancia), escritura con
transacciones cortas por análisis. `POST /api/v1/risk/batch-runs` lo lanza a mano (ADMIN).

## Agente de retención (opcional)

`ai.agent.enabled = false` por defecto. `POST /api/v1/students/{id}/retention-agent/drafts`
(CONSEJERO, PSICOLOGO, ADMIN): bucle de *tool use* con herramientas de solo lectura sobre el caso
seudonimizado (`get_academic_summary`, `get_attendance`, `get_risk_history`, `search_resources`) y
`submit_draft` para entregar el borrador (validado con JSON Schema). Cada llamada a herramienta queda
en `agent_tool_calls`. El agente nunca envía mensajes ni ejecuta acciones.

Ver `docs/modelo-predictivo.md` para el modelo propio (dataset, entrenamiento, ONNX y activación).

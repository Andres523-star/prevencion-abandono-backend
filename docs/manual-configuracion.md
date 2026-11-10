# Manual de configuración: reglas de riesgo y política de asistencia

Todo se cambia en caliente, sin desplegar: `PUT /api/v1/settings/{clave}` (ADMIN, con la versión actual para evitar
pisar un cambio ajeno) y `PUT /api/v1/.../attendance-policy`. Cada cambio queda auditado.

## Política de asistencia

Resolución: **materia > práctica clínica del programa > programa > valor por defecto** (4 faltas, aviso en la 2.ª,
crítico en la 3.ª, 3 días hábiles para justificar, tardanzas no suman).

| Endpoint | Para |
|---|---|
| `GET/PUT /api/v1/programs/{id}/attendance-policy` | El programa (`?clinical=true`: sus prácticas clínicas, solo ENF, MED, ODO) |
| `GET/PUT/DELETE /api/v1/subjects/{id}/attendance-policy` | Una materia (borrar = vuelve a la del programa) |
| `GET /api/v1/groups/{id}/attendance-policy` | La política efectiva de un grupo y de dónde sale |

```json
{"maxAbsences": 4, "warningThreshold": 2, "criticalThreshold": 3, "justificationWindowBusinessDays": 3, "tardiesPerAbsence": null}
```

Reglas: `1 ≤ aviso < crítico < máximo ≤ 50`; `tardiesPerAbsence` (2 a 10) = cuántas tardanzas suman una falta, o
`null`. Fijo e inmodificable: la falta cuenta por sesión (no por horas) y solo el docente aprueba justificaciones.

## Reglas de riesgo

Cada regla es un objeto JSON; el puntaje total (0-100) se compara con `risk.levels`:

| Clave | Valor por defecto | Qué hace |
|---|---|---|
| `risk.levels` | `{"medium": 30, "high": 60}` | Desde qué puntaje es MEDIO y ALTO |
| `risk.rules.low-average` | `{"enabled": true, "threshold": 3.0, "severeThreshold": 2.5, "points": 15, "severePoints": 25}` | Promedio del semestre bajo |
| `risk.rules.failing-subjects` | `{"enabled": true, "pointsPerSubject": 10, "maxPoints": 30}` | Materias con promedio < 3.0 |
| `risk.rules.absences` | `{"enabled": true, "warningPoints": 8, "criticalPoints": 15, "lostPoints": 25, "maxPoints": 40}` | Nivel de faltas según la política de cada materia |
| `risk.rules.tardies` | `{"enabled": true, "threshold": 3, "points": 5}` | Tardanzas acumuladas |
| `risk.rules.grade-trend` | `{"enabled": true, "pointsPerSubject": 5, "maxPoints": 15}` | Materias con notas a la baja |
| `risk.rules.conduct` | `{"enabled": true, "threshold": 1, "pointsPerObservation": 4, "maxPoints": 12}` | Observaciones de conducta |
| `risk.rules.wellbeing` | `{"enabled": true, "openReferralPoints": 8, "maxPoints": 10}` | Remisiones de bienestar abiertas (solo conteo) |
| `risk.rules.causes` | puntos por categoría, máx. 20 | Causas de riesgo abiertas |
| `risk.rules.socioeconomic-context` | `{"enabled": true, "combinedPoints": 4, "maxPoints": 8}` | Solo combinado con una señal académica o de asistencia. **El estrato nunca suma** |

Otros parámetros: `risk.watch-days` (vigilancia, 30), `risk.event-debounce-seconds` (120), `risk.ai-can-escalate`
(false: la IA no cambia el nivel), `risk.ml.active-version` (modelo propio; vacío = reglas), `ai.enabled`,
`ai.daily-token-budget`, `ai.min-level`, `ai.support-plan.min-level`, `ai.free-text.enabled` y
`ai.payload.include-sensitive-signals` (estos dos últimos, apagados hasta la validación jurídica).

**Cómo ajustar:** cambiar un valor, analizar unos estudiantes de prueba (`POST /students/{id}/analyze`) y revisar
`GET /risk/bias-metrics` para comprobar que el cambio no castiga a un grupo. La versión de las reglas (hash de la
configuración) queda en cada análisis, así se sabe con qué reglas se calculó. Detalle en
[motor-de-riesgo.md](motor-de-riesgo.md).

## Otros parámetros frecuentes

`attendance.edit-window-hours` (48), `justifications.appeal-days` (5), `alerts.escalation-hours` (48),
`analytics.min-cell-size` (10), `chat.retention-days` (730), `privacy.*` (plazos y copias). La lista completa con su
descripción: `GET /api/v1/settings`.

# Analítica, tablero y reportes (Fase 6)

## Mapa de módulos

| Módulo | Qué hace | API pública | Depende de |
|---|---|---|---|
| `analytics` | Indicadores agregados para los gráficos, supresión de celdas, caché, refresco de vistas y series históricas | `AnalyticsQueries`, `AnalyticsFilter`, `AnalyticsViewer`, `DropoutHistoryBook` | academic, records, justifications, retention, risk, settings, audit |
| `dashboard` | Pantalla inicial según el rol. No guarda datos | solo REST | analytics, alerts, notifications, risk, students, academic, audit |
| `reports` | Reportes asíncronos en PDF y Excel | solo REST | analytics, risk, students, records, academic, settings, audit |

`imports` suma el tipo `DROPOUT_HISTORY` (depende de `analytics`).

## Modelo de lectura

La analítica no cruza tablas de otros módulos en caliente. Lee tres vistas materializadas definidas en la
migración V6 (el cruce se hace una vez, al refrescar):

| Vista | Grano | Uso |
|---|---|---|
| `analytics_student_facts` | estudiante × periodo | deserción, semestres, riesgo, tendencia, totales |
| `analytics_attendance_cube` | materia × programa × semestre × periodo × nivel de riesgo | materias con más faltas, inscripciones cerca del límite |
| `analytics_retention_cube` | programa × semestre × periodo × mes × nivel × estado del caso | embudo de retención |

Decisiones que conviene conocer:

- **Deserción** = estudiante con estado `RETIRADO`. Se cuenta solo en su último periodo (`is_dropout`), para
  que un retiro no aparezca en todos los periodos que cursó. La fecha es el cierre de su caso de retención y,
  si no tiene, la última actualización del estudiante.
- **Población** = estudiantes con alguna inscripción. Quien nunca se inscribió no entra en las vistas.
- **Semestre** = el actual del estudiante: el histórico no se guarda.
- **Nivel de riesgo** = el último análisis, o `SIN_ANALISIS`. Es el nivel de hoy, también al mirar periodos
  anteriores.
- `analytics_attendance_cube` parte de `absence_counters`, no de `attendance_records`: el refresco no recorre
  los millones de filas de asistencia.

### Refresco e invalidación

1. Los eventos de dominio (`RiskAssessed`, `AbsenceRecorded`, `AbsenceRetracted`, `AttendanceThresholdReached`,
   `SubjectLostByAbsences`, `JustificationDecided`, `RetentionCaseChanged`) solo marcan
   `analytics_refresh_state.dirty`. El `UPDATE` únicamente escribe la primera vez.
2. Cada 30 s un job revisa la marca y refresca si pasó `analytics.refresh-min-seconds` (300) desde el último
   refresco. Agrupar es intencional: refrescar por cada falta costaría más que los datos que actualiza.
3. `analytics_refresh()` (`SECURITY DEFINER`, porque el rol de la app no es dueño de las vistas) usa
   `REFRESH MATERIALIZED VIEW CONCURRENTLY`: las lecturas siguen funcionando durante el refresco. Un bloqueo
   consultivo evita que dos instancias refresquen a la vez.
4. Al terminar sube la generación de la caché. A las 02:30 se refresca siempre, por los cambios que no
   publican evento (p. ej. una edición manual de un estudiante).

Los datos pueden tener hasta ~5 minutos de atraso; cada respuesta trae `refreshedAt`.

### Caché

`SharedCache` es un producto nuevo de la Abstract Factory `DistributedStateFactory` (Redis en producción,
Caffeine en dev y pruebas). La clave incluye generación, endpoint y un hash de filtros, alcance y tamaño mínimo
de celda: dos coordinadores nunca comparten respuesta. Se guarda la respuesta **ya suprimida**. Vida máxima
`analytics.cache-ttl-seconds` (300). Métrica `analytics.cache{result=hit|miss}`.

## Endpoints de analítica

Filtros comunes: `programId`, `semester`, `year`, `termId`, `from`, `to`, `riskLevel` (`ALTO`, `MEDIO`, `BAJO`,
`SIN_ANALISIS`). `from` y `to` eligen los periodos que se cruzan con el rango y, para los retiros, la fecha del
retiro.

| Endpoint | Gráfico |
|---|---|
| `GET /api/v1/analytics/dropout-by-program` | barras |
| `GET /api/v1/analytics/trend?granularity=month\|year` | líneas y área |
| `GET /api/v1/analytics/by-semester` | tortas |
| `GET /api/v1/analytics/risk-distribution` | estudiantes activos por nivel |
| `GET /api/v1/analytics/attendance-risk?limit=20` | materias con más faltas y cerca del límite |
| `GET /api/v1/analytics/retention-funnel` | embudo |
| `POST /api/v1/analytics/refresh` | fuerza el refresco (ADMIN) |

**Alcance:** ADMIN ve todo, PSICOLOGO sin programas es transversal, y COORDINADOR, CONSEJERO y PSICOLOGO con
programas ven solo los suyos. Pedir otro programa o no tener programas responde 403. DOCENTE y ESTUDIANTE no
tienen acceso.

### Supresión de celdas

`CellSuppressor` oculta los conteos entre 1 y `analytics.min-cell-size - 1` (10 por defecto): el valor llega
`null` con `suppressed: true`, y también se ocultan el porcentaje y los conteos que dependen de él. El cero no
se oculta. Si en una columna quedaría una sola celda oculta, se oculta además la menor visible (**supresión
complementaria**), para que no se deduzca restando del total.

**Riesgo residual:** la supresión protege cada respuesta, no la combinación de muchas consultas con filtros
distintos. Alguien con acceso podría comparar resultados para acercarse a una celda oculta. Por eso los
endpoints siguen restringidos a personal con alcance sobre ese programa.

## Series históricas

Tabla `dropout_history`, cargada con la importación `DROPOUT_HISTORY` (plantilla en
[importacion.md](importacion.md)). Son cifras ya agregadas: no hay estudiantes detrás.

- La serie se corta antes del primer periodo con datos en vivo, para no contar dos veces.
- No tiene nivel de riesgo ni periodo: con `riskLevel` o `termId` no aparece.
- Cada punto de `/trend` trae `source`: `LIVE`, `HISTORICAL` o `MIXED`.
- Por mes, los puntos en vivo no traen `enrolled` (la población mensual no existe); por año, sí.
- Por año el porcentaje suma los matriculados de cada fila; si la serie viene por semestre, equivale a un
  promedio ponderado semestral.

## Tablero

`GET /api/v1/dashboard/summary?size=20` devuelve `scope`, `totals`, `lists` (primera página de ALTO, MEDIO y
BAJO) y las últimas 5 notificaciones. `GET /api/v1/dashboard/students?level=&after=&size=` trae las páginas
siguientes (keyset).

| Rol | `scope` | Totales |
|---|---|---|
| ADMIN, PSICOLOGO sin programas | `GLOBAL` | modelo de lectura, alertas abiertas, % de abandono |
| COORDINADOR, CONSEJERO, PSICOLOGO con programas | `PROGRAMS` | igual, solo sus programas |
| DOCENTE | `GROUPS` | en vivo, solo estudiantes activos de sus grupos; sin alertas ni % de abandono |
| ESTUDIANTE | — | 403 (su portal es de la Fase 7) |

Las listas muestran código anónimo, programa, semestre, nivel, puntaje y fecha: sin nombre ni factores. El
tablero no aplica supresión porque es para personal con acceso a esos estudiantes. Las lecturas quedan
auditadas.

## Reportes

`POST /api/v1/reports` responde 202 con el id. `GET /reports/{id}` da el estado (`PENDIENTE`, `PROCESANDO`,
`LISTO`, `FALLIDO`, `EXPIRADO`) y `GET /reports/{id}/download-url` una URL prefirmada de 10 minutos (auditada).

| Tipo | Contenido |
|---|---|
| `GENERAL` | programas, semestres, riesgo, embudo y materias |
| `BY_PROGRAM`, `BY_SEMESTER`, `BY_YEAR` | agregados |
| `AT_RISK_STUDENTS` | listado por keyset; por defecto ALTO y MEDIO |
| `ATTENDANCE` | materias e inscripciones cerca del límite |

- **Alcance:** se resuelve al pedir y queda guardado en el trabajo, que se genera sin petición. Cada quien ve
  sus reportes (ADMIN, todos); un reporte ajeno responde 404.
- **Identidad:** `includeIdentity` (solo en listados) agrega el nombre y exige `X-Access-Reason`. Se audita la
  solicitud (con motivo), la generación (`REPORT_GENERATED`, con tipo y filas) y cada descarga. La generación
  se registra una sola vez por reporte: una fila de auditoría por estudiante serializaría la cadena.
- **Contenido:** los agregados llevan la supresión de celdas. Los listados nunca incluyen factores
  psicológicos, de salud ni socioeconómicos.
- **Confidencialidad:** todo reporte lleva aviso con la Ley 1581, fecha y usuario. PDF: marca de agua diagonal
  y aviso en cada página. Excel: aviso en cada hoja y en el encabezado y pie de impresión.
- **Memoria:** las filas se recorren por lotes de 1.000 y nunca están todas en memoria. Excel usa SXSSF
  (ventana de 100 filas, temporales comprimidos); PDF vuelca cada 200 filas y tiene un máximo de
  `reports.pdf-max-rows` (20.000), porque un PDF más largo no se usa: falla con `report_too_large`.
- **Vencimiento:** `reports.expiry-hours` (24). Un job horario borra el archivo; la descarga responde 410.
- **Cola:** `FOR UPDATE SKIP LOCKED` con lease de 15 minutos y hasta 3 intentos.

## Pruebas de aceptación

| Criterio | Prueba |
|---|---|
| Cada endpoint con datos sintéticos grandes | `AnalyticsIT`, `DashboardIT`, `ReportsIT` (1.200 estudiantes, cifras contra las tablas base) |
| Supresión de celdas pequeñas | `AnalyticsIT.smallCellsAreSuppressed`, `AnalyticsDomainTest` |
| ABAC | `AnalyticsIT.scopeIsEnforcedPerProgramAndRole`, `DashboardIT`, `ReportsIT.reportsAreOnlyVisibleToWhoeverAskedAndWithinScope` |
| Prueba de rendimiento | `AnalyticsVolumeIT` y `perf/` (Gatling); resultados en [rendimiento.md](rendimiento.md) |
| Excel de 100.000 filas sin agotar memoria | `ReportWritersTest.hundredThousandRowsFitInASmallHeap` (JVM aparte con 48 MB de heap) |

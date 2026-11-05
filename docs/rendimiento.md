# Rendimiento: asistencia a 5 millones de filas (Fase 3)

Prueba: `AttendanceVolumeIT`. Por defecto corre a escala reducida dentro de `./mvnw verify`
(~180.000 filas). La escala completa del criterio de aceptación se ejecuta con:

```bash
./mvnw verify -Dit.test=AttendanceVolumeIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dretencion.perf.students=34000
```

## Qué hace

1. Genera con `SyntheticDataGenerator` un periodo completo: 34.000 estudiantes, 6 programas ×
   2 semestres × 5 materias, grupos de 40 con clase 2 veces por semana y sus sesiones.
2. Inserta una fila de asistencia por (inscripción, sesión) en la partición del periodo: ~8 %
   faltas, ~4 % tardanzas.
3. `ANALYZE` y, para cada consulta que usa la aplicación, verifica con `EXPLAIN` que **no hay
   recorrido secuencial de la partición** y mide 200 ejecuciones con parámetros aleatorios
   (después de 20 de calentamiento). Falla si el p95 supera el umbral.

## Resultados (2026-11-04)

Entorno: portátil Windows 11, PostgreSQL 16 embebido (Zonky), sin ajustes de memoria. En un
PostgreSQL gestionado los tiempos absolutos cambian; lo importante es que los planes usan índices.

| Dato | Valor |
|---|---|
| Filas de asistencia en el periodo | **5.063.823** |
| Estudiantes / inscripciones / sesiones | 34.000 / 159.475 / 135.276 |
| Generación de datos sintéticos | 107 s |
| Carga de asistencia (un `INSERT … SELECT`) | 782 s |

| Consulta (uso en la app) | Índice | p50 | p95 | Umbral p95 |
|---|---|---|---|---|
| Registros de una inscripción (recalcular el contador) | `(term_id, enrollment_id)` | 1,28 ms | 2,30 ms | 50 ms |
| Asistencia de una sesión (pantalla del docente) | `(term_id, session_id)` | 8,56 ms | 14,19 ms | 50 ms |
| Historial del estudiante | `(student_id, term_id)` | 3,03 ms | 5,12 ms | 50 ms |
| Faltas vencidas (job diario) | parcial `justification_deadline` donde `FALTA` y `NINGUNA` | 0,41 ms | 0,60 ms | 200 ms |

Escala reducida (la que corre en CI): 178.400 filas, p95 entre 1,1 y 5,6 ms.

## Decisiones que lo sostienen

- **Partición por periodo** (`LIST (term_id)`): las consultas del día a día filtran por periodo y
  tocan una sola partición; los periodos cerrados se pueden archivar o separar sin afectar al
  activo.
- El contador por inscripción (`absence_counters`) evita recontar en cada lectura: la consulta de
  2 ms solo ocurre al cambiar la asistencia de esa inscripción.
- El índice parcial del vencimiento solo contiene faltas sin justificar con plazo, una fracción
  pequeña de la tabla.
- La carga masiva de 5 M filas no es un caso de uso de la app (la asistencia llega sesión por
  sesión); su tiempo solo indica el costo de preparar la prueba.

# Rendimiento: analítica y tablero (Fase 6)

Objetivo: **p95 < 500 ms con 50.000 estudiantes y 5 millones de filas de asistencia.** Prueba:
`AnalyticsVolumeIT` (a escala reducida dentro de `./mvnw verify`) y la simulación de Gatling en `perf/`.
La escala completa se ejecuta con:

```bash
./mvnw verify -Dit.test=AnalyticsVolumeIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dretencion.perf.students=50000 -Dretencion.perf.attendance=true -Dretencion.perf.gatling=true
```

Contra un ambiente desplegado (con un JWT de ADMIN de ese ambiente):

```bash
./mvnw -f perf/pom.xml gatling:test -DbaseUrl=https://staging.example.com -DauthToken=TOKEN -DtermId=UUID -Dusers=50 -DdurationSeconds=120
```

## Resultados (2026-11-05)

Entorno: portátil Windows 11 con todo en la misma máquina (backend, PostgreSQL 16 embebido sin ajustes y
Gatling). En un PostgreSQL gestionado los tiempos absolutos cambian.

| Dato | Valor |
|---|---|
| Estudiantes generados / con inscripción | 50.000 / 47.098 |
| Inscripciones del periodo | 235.490 |
| Filas de asistencia | **7.477.085** |
| Filas de `analytics_student_facts` / `analytics_attendance_cube` | 47.098 / 180 |
| Refresco de las vistas (`CONCURRENTLY`) | 38 s |

**Consultas sin caché** (el peor caso, justo después de un refresco), 100 mediciones con filtros al azar:

| Consulta | p50 | p95 |
|---|---|---|
| Deserción por programa | 3,8 ms | 42,2 ms |
| Tendencia mensual | 2,1 ms | 4,6 ms |
| Tendencia anual | 7,3 ms | 23,1 ms |
| Por semestre | 9,6 ms | 47,2 ms |
| Distribución de riesgo | 8,7 ms | 62,2 ms |
| Materias con más faltas | 0,6 ms | 0,9 ms |
| Embudo de retención | 0,3 ms | 0,5 ms |
| Totales del tablero | 24,9 ms | 28,0 ms |
| Deserción sin filtro de periodo | 39,6 ms | 43,2 ms |

**Carga con Gatling** por HTTP real contra Tomcat: 50 usuarios nuevos por segundo durante 120 s, cada uno con un
gráfico de filtros al azar más el tablero completo.

| Métrica | Valor |
|---|---|
| Peticiones / fallidas | 12.000 / 0 |
| Rendimiento | 100 peticiones/s |
| p50 / p75 / p95 / p99 | 7 / 11 / **17** / 59 ms |
| Máximo | 922 ms |

## Planes revisados con EXPLAIN

- `analytics_student_facts` con programa y periodo usa `ix_analytics_facts_program` (Bitmap Index Scan). Sin esos
  filtros el recorrido secuencial es lo correcto: la consulta necesita casi toda la vista.
- `analytics_attendance_cube` tiene 180 filas: PostgreSQL prefiere leerla entera y es lo óptimo.
- El refresco es lo único que toca las tablas grandes, y no lee `attendance_records` sino `absence_counters`.

## Límites de esta medición

- La carga de Gatling mide sobre todo respuestas con caché; por eso las consultas se miden aparte sin ella.
- El volumen es de **un** periodo. Con años de historia `analytics_student_facts` crece en una fila por estudiante
  y periodo: las consultas por periodo no cambian, pero las que no filtran periodo sí crecerán.
- El caché de esta prueba está en memoria: Redis agrega un viaje de red que no se midió.
- Falta repetirla sobre PostgreSQL gestionado de Render y con Redis (Fase 7).


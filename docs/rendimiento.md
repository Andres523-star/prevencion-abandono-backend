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

# Importación masiva (CSV / Excel)

`POST /api/v1/imports` (multipart, solo ADMIN) con los parámetros:

| Parámetro | Obligatorio | Descripción |
|---|---|---|
| `file` | sí | CSV en UTF-8 (separador `,` o `;`, se detecta solo) o Excel `.xlsx` (primera hoja). Máx. 20 MB / 200.000 filas. |
| `type` | sí | `TEACHERS`, `SUBJECTS`, `GROUPS`, `STUDENTS`, `ENROLLMENTS`, `GRADES` o `DROPOUT_HISTORY` |
| `dryRun` | no | `true` = solo valida (no escribe nada) |
| `mapping` | no | JSON `{"campo": "Encabezado del archivo"}` para archivos con otros nombres de columna |

- Archivos de hasta 256 KB se procesan en la petición (**200** con el resultado). Los más grandes
  quedan en cola (**202**) y los procesa un worker en segundo plano: consultar `GET /api/v1/imports/{id}`
  hasta `COMPLETED` o `FAILED`.
- `GET /api/v1/imports/{id}/report` descarga el resultado por fila (`fila,resultado,mensaje`). El
  reporte **no contiene valores** de la fila (pueden ser datos personales), solo el campo y el problema.
- `GET /api/v1/imports/templates/{type}` descarga la plantilla oficial con una fila de ejemplo.
- **Idempotencia:** cada tipo tiene una clave natural; reimportar el mismo archivo deja las filas como
  `UNCHANGED`. Cada fila va en su propia transacción: un error no revierte las demás.
- El archivo subido se borra de la base de datos al terminar el procesamiento.
- El formato se valida por contenido (*magic bytes*): un `.xlsx` que no es un ZIP se rechaza.

**Orden recomendado:** docentes → materias → grupos → estudiantes → inscripciones (los grupos
referencian docentes y materias; las inscripciones, estudiantes y grupos). El periodo debe existir
(`POST /api/v1/terms`).

## Plantillas (campos canónicos)

Los encabezados se comparan sin tildes, sin distinguir mayúsculas y con espacios como `_`
("Número de Documento" = `numero_de_documento`). Entre paréntesis, alias aceptados por defecto.
`*` = obligatorio.

### TEACHERS — clave: `correo`
| Campo | Ejemplo |
|---|---|
| `correo`* (email, correo_electronico) | docente@universidad.edu.co |
| `nombre`* (nombres, nombre_completo) | Carlos Ramírez |

Crea el usuario con rol DOCENTE antes de su primer ingreso; al entrar por Supabase con ese correo
verificado, la cuenta se vincula. Nunca quita roles existentes.

### SUBJECTS — clave: `programa` + `codigo`
| Campo | Ejemplo |
|---|---|
| `programa`* (codigo_programa) | MED |
| `codigo`* (codigo_materia) | MED-201 |
| `nombre`* (nombre_materia, materia) | Anatomía II |
| `creditos`* | 4 |
| `semestre_sugerido`* (semestre) | 2 |
| `prerrequisitos` | MED-101;MED-102 |

Los prerrequisitos deben existir o estar en una fila anterior del mismo archivo.

### GROUPS — clave: `programa` + `materia` + `periodo` + `grupo`
| Campo | Ejemplo |
|---|---|
| `programa`* | MED |
| `materia`* (codigo_materia) | MED-201 |
| `periodo`* (codigo_periodo) | 2026-2 |
| `grupo`* (codigo_grupo) | A |
| `cupo`* (capacidad) | 40 |
| `correo_docente` (docente) | docente@universidad.edu.co |
| `tipo` (tipo_grupo) | `REGULAR` o `PRACTICA_CLINICA` (solo ENF, MED, ODO) |
| `horario` | `LUN 07:00-09:00 A-101; MIE 07:00-09:00 A-101` |

Días: LUN, MAR, MIE, JUE, VIE, SAB, DOM (también "Lunes", "Miércoles"...). El aula es opcional.

### STUDENTS — clave: `documento`
| Campo | Ejemplo |
|---|---|
| `tipo_documento`* (tipo_doc, tipo_identificacion) | CC, TI, CE, PA, PEP, PPT |
| `documento`* (numero_documento, cedula, identificacion) | 1020304050 |
| `nombre`* (nombres, nombre_completo) | Ana María Pérez Gómez |
| `correo` (email) | ana.perez@correo.com |
| `telefono` (celular, movil) | 3001234567 |
| `programa`* | MED |
| `semestre`* (semestre_actual) | 3 |
| `estado` | ACTIVO (defecto), RETIRADO, APLAZADO, GRADUADO |
| `fecha_ingreso`* | 2025-01-20 o 20/01/2025 |
| `estrato` | 1–6 |
| `trabaja` | sí / no |
| `personas_a_cargo` (dependientes) | 0–20 |
| `transporte` (medio_transporte) | A_PIE, BICICLETA, TRANSPORTE_PUBLICO, MOTO, CARRO, OTRO |
| `version_consentimiento` | 2026.1 (registra el consentimiento de tratamiento académico) |

El documento se normaliza (`1.020.304-050` = `1020304050`) y se busca por blind index. Nombre,
documento, correo y teléfono se guardan cifrados. Si ya existe un usuario con el mismo correo, se
vincula como estudiante.

### ENROLLMENTS — clave: `documento` + `programa` + `materia` + `periodo` + `grupo`
| Campo | Ejemplo |
|---|---|
| `documento`* | 1020304050 |
| `programa`* | MED |
| `materia`* | MED-201 |
| `periodo`* | 2026-2 |
| `grupo`* | A |

Respeta el cupo del grupo. Una inscripción retirada se reactiva.

### GRADES — clave: inscripción (`documento` + `programa` + `materia` + `periodo` + `grupo`) + `fecha` + `descripcion`
| Campo | Ejemplo |
|---|---|
| `documento`* | 1020304050 |
| `programa`* | MED |
| `materia`* | MED-201 |
| `periodo`* | 2026-2 |
| `grupo`* | A |
| `nota`* | 3.8 (o `3,8`; escala 0.0–5.0, se redondea a un decimal) |
| `fecha`* | 2026-09-15 |
| `descripcion` | Parcial 1 |

El estudiante debe estar inscrito en el grupo. Reimportar el archivo no duplica notas: si cambia
el valor de una nota con la misma clave, se actualiza. La plataforma no calcula la nota final
oficial; solo usa estas notas para los indicadores de riesgo.

### DROPOUT_HISTORY — clave: `programa` + `anio` + `mes` + `semestre`

Series históricas de deserción de periodos anteriores a la plataforma, ya agregadas (sin datos de
estudiantes). Alimentan la tendencia de `/analytics/trend`; ver [analitica-y-reportes.md](analitica-y-reportes.md).

| Campo | Ejemplo |
|---|---|
| `programa`* (codigo_programa) | MED |
| `anio`* (año, year) | 2023 |
| `mes` (month) | 2 (si falta, enero) |
| `semestre` | 3 (déjelo vacío si la serie no distingue semestre) |
| `matriculados`* (inscritos, enrolled) | 420 |
| `desertores`* (retirados, dropouts) | 37 (no puede superar a matriculados) |
| `periodo` (etiqueta) | 2023-1 |

Use una sola forma por programa y mes: por semestre o sin semestre. Si mezcla las dos, se suman.

## Adaptar el formato de los datos reales

Los datos reales aún no tienen formato definido. Sin tocar código:

1. **Por trabajo:** parámetro `mapping`, p. ej. `{"documento":"Num Identificación","programa":"Carrera"}`.
2. **Permanente:** alias en la configuración (`application.yml` o variables de entorno):
   ```yaml
   app:
     imports:
       aliases:
         documento: [num_identificacion, doc_estudiante]
         programa: [carrera, cod_carrera]
   ```

## Datos sintéticos (perfil dev)

`APP_SYNTHETIC_ENABLED=true` (y opcionalmente `APP_SYNTHETIC_STUDENTS=50000`) genera al arrancar, si
la base de datos no tiene estudiantes: periodo activo, festivos de Colombia, docentes, plan de 10
semestres × 4 materias por programa, grupos con horario, 50.000 estudiantes con Datafaker (identidad
cifrada, perfil socioeconómico), inscripciones y sesiones de clase. Nunca corre con el perfil `prod`.

# Guía para conectar los datos reales

## Orden de carga

Cada importación es idempotente por clave natural y se puede repetir. Empiece **siempre** con `dryRun=true`.

1. `TEACHERS` → 2. `SUBJECTS` → 3. `GROUPS` (con horario) → 4. Generar sesiones (`POST /groups/{id}/sessions/generate`)
→ 5. `STUDENTS` → 6. `ENROLLMENTS` → 7. `GRADES` → 8. `DROPOUT_HISTORY` (series históricas, opcional).

Plantillas y campos: [importacion.md](importacion.md). Antes del primer periodo: festivos
(`POST /holidays/colombia/{año}`), periodo (`POST /terms`) y políticas de asistencia ([manual-configuracion.md](manual-configuracion.md)).

## Si los encabezados son otros

- Puntual: parámetro `mapping` con `{"campo": "Encabezado del archivo"}`.
- Permanente: alias en `app.imports.aliases` (configuración), p. ej. `documento: [CEDULA_EST, NRO_DOC]`.

## Validación

1. `dryRun=true` sobre el archivo completo: el reporte lista fila y motivo, sin valores (no repite datos personales).
2. Corregir en el origen y repetir hasta cero errores.
3. Cargar en **staging** y revisar con el equipo académico: conteos por programa, grupos sin docente, estudiantes
   sin inscripción, políticas aplicadas.
4. Cargar en producción con los consentimientos registrados (`channel = IMPORTACION`).

## Cuidados

- Nunca envíe los archivos por correo ni chat: súbalos directamente por la API (ADMIN, cifrado en tránsito); el
  archivo se borra al terminar.
- Los documentos se normalizan antes del blind index; un mismo número con distinto tipo de documento colisiona
  (pendiente conocido).
- Las fechas van en `AAAA-MM-DD` y la zona de negocio es America/Bogota.

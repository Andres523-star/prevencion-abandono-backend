# Catálogo de endpoints REST · Demo Backend

Todas las rutas van bajo `/api`. Salvo las marcadas como **públicas**, exigen
`Authorization: Bearer <token>` y aplican el alcance del rol (ver `common/Alcance.java` y
[SEGURIDAD_DEMO.md](SEGURIDAD_DEMO.md)). Los errores responden `{"error": "mensaje"}`.

## Autenticación

| Método y ruta | Acceso | Descripción |
|---|---|---|
| `POST /api/auth/login` | pública | `{email, password, rol, sede}`. La sede debe coincidir con la registrada (salvo Rector). Demasiados fallos → 429 con `Retry-After` |
| `POST /api/auth/register` | pública | `{nombre, apellido, email, password, sede, programa}`. Solo crea profesores; contraseña de 8+ caracteres con letras y números; 409 si el correo existe; límite por IP |
| `GET /api/auth/opciones-registro` | pública | Sedes y programas de cada sede para el formulario de registro |
| `POST /api/auth/logout` | con token | Revoca el token en el servidor (204) |
| `GET /api/auth/me` | con token | Datos de la sesión |

## Estudiantes

| Método y ruta | Descripción |
|---|---|
| `GET /api/estudiantes` | Listado con filtros (`search`, `carrera`, `sede`, `facultad`, `semestre`, `categoria`, `riesgo`, `estado`, `grupo`, `limit`), siempre dentro del alcance del rol. El estudiante solo se ve a sí mismo |
| `GET /api/estudiantes/{id}` | Ficha del estudiante (403 fuera de alcance) |
| `POST /api/estudiantes` | Registro de un estudiante (403 para la cuenta de estudiante) |
| `GET /api/estudiantes/{id}/perfil` | Perfil e historial académico |
| `GET /api/estudiantes/{id}/acompanamiento`, `PUT .../{accionId}` | Plan de acompañamiento |
| `GET /api/catalogos` | Sedes, programas y filtros disponibles según el alcance |

## Análisis e IA

| Método y ruta | Descripción |
|---|---|
| `POST /api/analizar/{estudianteId}` | Plan de acción para el profesor (Claude Sonnet 5.5 si hay `ANTHROPIC_API_KEY`, si no motor local). 403 fuera de alcance |
| `POST /api/ia/consultar`, `GET /api/ia/capacidades`, `GET/DELETE /api/ia/historial` | Asistente de la plataforma (respuestas locales dentro del alcance) |

## Reportes y exportación

| Método y ruta | Descripción |
|---|---|
| `GET /api/reportes` | Métricas consolidadas dentro del alcance |
| `GET /api/export/estudiantes.csv`, `/reporte.csv`, `/todo.zip` | Descargas dentro del alcance. La cuenta de estudiante solo exporta su propio registro |
| `GET /api/export/estudiantes/{id}/ficha.csv`, `/ficha.html` | Ficha individual (403 fuera de alcance) |

## Acompañamiento (chat), notificaciones y justificaciones

| Método y ruta | Descripción |
|---|---|
| `GET /api/chats` | Conversaciones del usuario |
| `GET /api/chats/{estudianteId}/mensajes` | Mensajes; los que tienen adjunto traen `archivoFirma` |
| `POST /api/chats/{estudianteId}/mensajes` | `{texto, archivoBase64, nombreArchivo, mime}`. Adjuntos: PDF, Word `.docx` sin macros o audio, máx. 5 MB, validados por contenido |
| `GET /api/chats/adjuntos/{archivoId}?firma=...` | Descarga con enlace firmado (sin firma válida y vigente → 403) |
| `GET /api/notificaciones`, `GET /resumen`, `POST /{id}/leer`, `POST /leer-todas` | Campanita |
| `GET /api/justificaciones`, `GET /correo`, `POST` | Justificaciones (el estudiante ve y radica las suyas) |
| `GET /api/salud` | pública. Estado del servicio |

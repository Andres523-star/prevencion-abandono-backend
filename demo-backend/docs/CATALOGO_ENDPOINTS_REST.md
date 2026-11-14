# Catalogo de Endpoints REST · Demo Backend

## Autenticacion
- `POST /api/auth/login`: Autenticacion con rol y sede por defecto.
- `POST /api/auth/register`: Registro de nuevos usuarios de prueba.

## Estudiantes
- `GET /api/estudiantes`: Listado paginado con filtros de busqueda.
- `GET /api/estudiantes/{id}`: Ficha completa del estudiante y plan de accion.
- `POST /api/estudiantes`: Registro de nuevos estudiantes.

## Analitica y Reportes
- `GET /api/reportes`: Metricas consolidadas por sede y programa.
- `GET /api/risk/analisis`: Evaluacion semaforizada del periodo.

## Acompanamiento
- `GET /api/chat/conversaciones`: Historial de conversaciones de soporte.
- `POST /api/chat/mensajes`: Envio de mensajes y respuesta automatica.

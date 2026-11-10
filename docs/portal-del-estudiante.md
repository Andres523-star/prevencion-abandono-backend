# Portal del estudiante (API)

Todo lo del portal exige el rol ESTUDIANTE y trabaja **solo sobre sus propios datos**: el estudiante se resuelve
desde su usuario, nunca desde un parámetro. Un id ajeno responde 403 o 404 (no revela si existe).

## Pantallas

| Endpoint | Qué muestra |
|---|---|
| `GET /api/v1/portal/overview` | Materias inscritas: docente, su enlace de cita, faltas con la política que aplica (límite, aviso, crítico, plazo para justificar, si las tardanzas suman), cuántas le quedan, notas y promedio |
| `GET /api/v1/portal/absences` | Historial de faltas y tardanzas con el estado de la justificación, el plazo y si aún puede justificar |
| `GET /api/v1/portal/support` | Apoyo aprobado para él y servicios de la universidad |

## Acciones (en su módulo)

| Acción | Endpoint |
|---|---|
| Justificar una falta | `POST /api/v1/justifications` y `POST /api/v1/justifications/{id}/evidences` (PDF/JPG/PNG, antivirus) |
| Ver mis justificaciones | `GET /api/v1/me/justifications` |
| Apelar una justificación rechazada | `POST /api/v1/justifications/{id}/appeal` (una vez, `justifications.appeal-days` = 5 días) |
| "Necesito ayuda / estoy pensando en retirarme" | `POST /api/v1/students/me/retention-requests` (abre un caso de retención) |
| Campanita | `GET /api/v1/notifications`, `/unread-count`, `POST /{id}/read`, `/read-all` (justificación decidida, mensaje nuevo, solicitud respondida) |
| Chats con docentes | `GET /api/v1/chats` (incluye el enlace de cita del docente), `GET/POST /api/v1/chats/{id}/messages` |
| Consentimientos | `GET/POST /api/v1/students/me/consents` |
| Solicitudes sobre mis datos | `POST/GET /api/v1/students/me/data-requests`, `GET /api/v1/data-requests/{id}/export-url` |
| Finalidades y encargados | `GET /api/v1/privacy/processing-activities` |

## Apelación

La justificación rechazada vuelve a quedar **pendiente** (la falta sigue contando igual), el docente actual del
grupo recibe una notificación `JUSTIFICACION_APELADA` y decide con el mismo endpoint de siempre
(`POST /justifications/{id}/decision`). Esa decisión es definitiva: no hay segunda apelación. Ningún otro rol
decide, igual que en la primera revisión.

## Lenguaje

El portal **nunca** muestra nivel de riesgo, puntaje, probabilidad ni factores:

- Las faltas se dicen con cifras y lo que falta: "Tienes 2 faltas; el límite es 4", "Te queda 1 falta antes del
  límite", "Vas bien con tu asistencia".
- La tendencia de notas, en palabras: "Tus notas vienen mejorando".
- `/portal/support` solo muestra **planes aprobados por una persona** (consejero, coordinador o psicólogo), con su
  mensaje redactado para el estudiante y los recursos que cita. Un plan propuesto por las reglas o la IA no llega
  al estudiante hasta que alguien lo revisa.

Lo prueba `PortalIT` (incluida la ausencia de "riesgo", "alto", "crítico", "probabilidad", "%" en las respuestas).

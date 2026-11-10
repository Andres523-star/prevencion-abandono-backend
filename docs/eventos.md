# Catálogo de eventos de dominio

Publicados con `ApplicationEventPublisher` dentro de la transacción y entregados después de confirmarla. Los
`@ApplicationModuleListener` corren en otro hilo (cola acotada, ver [dimensionamiento.md](dimensionamiento.md)) y
quedan en el registro de publicaciones (`event_publication`) hasta completarse: si un listener falla, se reintenta al
reiniciar. Los marcados con `@Externalized` salen a RabbitMQ cuando `APP_RABBIT_ENABLED=true`.

| Evento | Publica | Consumen | Para qué |
|---|---|---|---|
| `UserProvisioned` | users | students | Vincular el estudiante por correo verificado |
| `SessionRevoked` | auth, users | realtime | Cerrar los WebSockets de la sesión |
| `SettingChanged` | settings | settings | Invalidar la caché de parámetros |
| `SecurityDeniedEvent` | common | audit | Auditar los 403 |
| `GroupTeacherChanged` | academic | justifications | Pasar las justificaciones pendientes al nuevo docente |
| `AbsenceRecorded` | records | mailing, risk, analytics | Aviso de falta, reanálisis, refresco de vistas |
| `AbsenceRetracted` | records | mailing, analytics | Cancelar el aviso |
| `AttendanceThresholdReached` | records | risk, alerts, notifications, analytics | Plan de apoyo, alerta, campanita del docente |
| `SubjectLostByAbsences` | records | risk, alerts, analytics | Ídem para la pérdida por inasistencia |
| `GradeRecorded` | records | risk | Reanálisis |
| `JustificationSubmitted` | justifications | notifications | Campanita del docente |
| `JustificationDecided` | justifications | mailing, risk, notifications, analytics | Aviso al estudiante, vigilancia si se rechaza |
| `JustificationAppealed` | justifications | notifications | Campanita del docente |
| `RiskAssessed` | risk | alerts, notifications, retention, analytics | Alerta, campanita, caso automático, refresco |
| `AlertAssigned` | alerts | notifications | Campanita del responsable |
| `ChatMessageSent` | chat | chat (entrega), notifications | Entrega en tiempo real y campanita |
| `InterventionAssigned` | interventions | notifications | Campanita del responsable |
| `RetentionFollowUpDue` | retention | notifications | Recordatorio de seguimiento o reingreso |
| `RetentionCaseChanged` | retention | analytics | Refresco del embudo |
| `DataRequestSubmitted` | privacy | notifications | Aviso a quienes atienden solicitudes |
| `DataRequestAnswered` | privacy | notifications | Aviso al titular |
| `StudentDataSuppressed` | privacy | chat, notifications | Purgar chats y campanita del titular |

Ningún evento lleva nombres, documentos, correos ni texto libre: solo ids, códigos y cifras.

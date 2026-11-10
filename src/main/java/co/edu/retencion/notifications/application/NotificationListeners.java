package co.edu.retencion.notifications.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.SubjectInfo;
import co.edu.retencion.alerts.AlertAssigned;
import co.edu.retencion.chat.ChatMessageSent;
import co.edu.retencion.interventions.InterventionAssigned;
import co.edu.retencion.justifications.JustificationDecided;
import co.edu.retencion.justifications.JustificationSubmitted;
import co.edu.retencion.notifications.domain.Notification;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.AttendanceThresholdReached;
import co.edu.retencion.records.RecordsSnapshot;
import co.edu.retencion.records.StudentRecords;
import co.edu.retencion.retention.RetentionFollowUpDue;
import co.edu.retencion.risk.RiskAssessed;
import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.RiskQueries;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Traduce eventos de dominio a notificaciones. Para los docentes solo construye motivos académicos
 * (materia, promedio, tendencia, faltas): los factores de bienestar, salud y socioeconómicos nunca
 * llegan a sus notificaciones.
 */
@Component
class NotificationListeners {

    private final NotificationService notifications;
    private final StudentRecords records;
    private final AcademicDirectory academic;
    private final StudentRegistry students;
    private final RiskQueries risk;
    private final co.edu.retencion.users.UserDirectory users;

    NotificationListeners(NotificationService notifications, StudentRecords records, AcademicDirectory academic,
                          StudentRegistry students, RiskQueries risk, co.edu.retencion.users.UserDirectory users) {
        this.users = users;
        this.notifications = notifications;
        this.records = records;
        this.academic = academic;
        this.students = students;
        this.risk = risk;
    }

    /** Riesgo MEDIO o ALTO: suena la campanita del docente de cada materia afectada. */
    @ApplicationModuleListener
    void on(RiskAssessed event) {
        if (event.level() == RiskLevel.BAJO) {
            return;
        }
        RecordsSnapshot snapshot = records.snapshot(event.studentId(), null);
        for (RecordsSnapshot.SubjectSnapshot s : snapshot.subjects()) {
            boolean signal = s.belowPassing() || "BAJA".equals(s.trend())
                    || (s.attendanceLevel() != null && s.attendanceLevel() != AttendanceLevel.NINGUNO);
            if (!signal) {
                continue;
            }
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("subject", s.subjectName());
            reason.put("groupId", s.groupId());
            if (s.average() != null) {
                reason.put("average", s.average());
            }
            reason.put("trend", s.trend());
            reason.put("absences", s.countedAbsences());
            reason.put("maxAbsences", s.maxAbsences());
            notifications.notify(teacherOf(s.groupId()), Notification.Type.RIESGO_ESTUDIANTE, event.studentId(),
                    event.level().name(), reason, event.assessmentId());
        }
    }

    @ApplicationModuleListener
    void on(AttendanceThresholdReached event) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("subject", subjectName(event.groupId()));
        reason.put("groupId", event.groupId());
        reason.put("absences", event.countedAbsences());
        reason.put("maxAbsences", event.maxAbsences());
        reason.put("attendanceLevel", event.level().name());
        String level = risk.latest(event.studentId()).map(r -> r.level().name()).orElse(null);
        notifications.notify(teacherOf(event.groupId()), Notification.Type.RIESGO_ESTUDIANTE, event.studentId(), level,
                reason, event.enrollmentId());
    }

    @ApplicationModuleListener
    void on(JustificationSubmitted event) {
        notifications.notify(event.teacherUserId(), Notification.Type.JUSTIFICACION_PENDIENTE, event.studentId(), null,
                Map.of("subject", String.valueOf(subjectName(event.groupId()))), event.justificationId());
    }

    @ApplicationModuleListener
    void on(co.edu.retencion.justifications.JustificationAppealed event) {
        notifications.notify(event.teacherUserId(), Notification.Type.JUSTIFICACION_APELADA, event.studentId(), null,
                Map.of("subject", String.valueOf(subjectName(event.groupId()))), event.justificationId());
    }

    @ApplicationModuleListener
    void on(co.edu.retencion.privacy.DataRequestSubmitted event) {
        for (UUID admin : users.activeUsersWithRole(co.edu.retencion.common.security.Role.ADMIN, null)) {
            notifications.notify(admin, Notification.Type.SOLICITUD_DATOS, null, null, Map.of("type", event.type()),
                    event.requestId());
        }
    }

    @ApplicationModuleListener
    void on(co.edu.retencion.privacy.DataRequestAnswered event) {
        notifications.notify(event.requesterUserId(), Notification.Type.SOLICITUD_DATOS_RESPONDIDA, null, null,
                Map.of("status", event.status()), event.requestId());
    }

    @ApplicationModuleListener
    void on(co.edu.retencion.privacy.StudentDataSuppressed event) {
        notifications.forget(event.userId(), event.studentId());
    }

    @ApplicationModuleListener
    void on(JustificationDecided event) {
        students.findById(event.studentId()).map(StudentInfo::userId).ifPresent(user -> notifications.notify(user,
                Notification.Type.JUSTIFICACION_DECIDIDA, event.studentId(), null,
                Map.of("subject", String.valueOf(event.subjectName()), "approved", event.approved()),
                event.justificationId()));
    }

    @ApplicationModuleListener
    void on(AlertAssigned event) {
        notifications.notify(event.recipient(), event.escalated() ? Notification.Type.ALERTA_ESCALADA
                : Notification.Type.ALERTA_ASIGNADA, event.studentId(), event.level(),
                Map.of("level", event.level(), "kind", event.kind()), event.alertId());
    }

    @ApplicationModuleListener
    void on(ChatMessageSent event) {
        for (UUID recipient : event.recipientIds()) {
            notifications.notify(recipient, Notification.Type.NUEVO_MENSAJE, event.studentId(), null,
                    Map.of("conversationId", event.conversationId()), event.conversationId());
        }
    }

    @ApplicationModuleListener
    void on(InterventionAssigned event) {
        notifications.notify(event.responsibleId(), Notification.Type.INTERVENCION_ASIGNADA, event.studentId(), null,
                Map.of("type", event.type()), event.interventionId());
    }

    @ApplicationModuleListener
    void on(RetentionFollowUpDue event) {
        notifications.notify(event.counselor(), "REINGRESO".equals(event.kind()) ? Notification.Type.CAMPANA_REINGRESO
                : Notification.Type.SEGUIMIENTO_RETENCION, event.studentId(), null,
                Map.of("offsetDays", event.offsetDays(), "kind", event.kind()), event.caseId());
    }

    private UUID teacherOf(UUID groupId) {
        return academic.findGroup(groupId).map(GroupInfo::teacherUserId).orElse(null);
    }

    private String subjectName(UUID groupId) {
        Optional<GroupInfo> group = academic.findGroup(groupId);
        return group.flatMap(g -> academic.findSubject(g.subjectId())).map(SubjectInfo::name).orElse(null);
    }
}

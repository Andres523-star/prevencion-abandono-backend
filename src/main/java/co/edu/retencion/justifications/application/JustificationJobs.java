package co.edu.retencion.justifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.retencion.academic.GroupTeacherChanged;

/**
 * Tareas del módulo: vencimiento diario de faltas no justificadas y traspaso de pendientes al
 * nuevo docente cuando el grupo cambia de docente.
 */
@Component
class JustificationJobs {

    private static final Logger log = LoggerFactory.getLogger(JustificationJobs.class);

    private final JustificationService justifications;

    JustificationJobs(JustificationService justifications) {
        this.justifications = justifications;
    }

    @Scheduled(cron = "0 5 0 * * *", zone = "America/Bogota")
    void expireOverdue() {
        int expired = justifications.expireOverdue();
        if (expired > 0) {
            log.info("{} faltas pasaron a VENCIDA por plazo de justificación cumplido", expired);
        }
    }

    @ApplicationModuleListener
    void on(GroupTeacherChanged event) {
        if (event.newTeacher() != null) {
            justifications.reassignPending(event.groupId(), event.newTeacher());
        }
    }
}

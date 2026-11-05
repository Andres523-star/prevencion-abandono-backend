package co.edu.retencion.retention.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recordatorios diarios de seguimientos vencidos (07:00 Bogotá). */
@Component
class RetentionJobs {

    private final RetentionService retention;

    RetentionJobs(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${app.retention.reminder-cron:0 0 7 * * *}", zone = "America/Bogota")
    void reminders() {
        retention.remindDueFollowUps();
    }
}

package co.edu.retencion.analytics.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.analytics.DropoutHistoryBook;
import co.edu.retencion.analytics.domain.DropoutHistory;
import co.edu.retencion.analytics.domain.DropoutHistoryRepository;
import co.edu.retencion.common.data.UpsertOutcome;

/** Carga idempotente de la serie histórica (por clave natural programa + mes + semestre). */
@Service
class DropoutHistoryService implements DropoutHistoryBook {

    private final DropoutHistoryRepository history;
    private final AnalyticsRefresher refresher;
    private final Clock clock;

    DropoutHistoryService(DropoutHistoryRepository history, AnalyticsRefresher refresher, Clock clock) {
        this.history = history;
        this.refresher = refresher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UpsertOutcome upsert(UUID programId, YearMonth month, Integer semester, int enrolled, int dropouts, String label,
                                UUID actor) {
        LocalDate period = month.atDay(1);
        Optional<DropoutHistory> existing = semester == null
                ? history.findByProgramIdAndPeriodMonthAndSemesterIsNull(programId, period)
                : history.findByProgramIdAndPeriodMonthAndSemester(programId, period, semester);
        UpsertOutcome outcome;
        if (existing.isPresent()) {
            if (!existing.get().update(enrolled, dropouts, label, actor, clock.instant())) {
                return UpsertOutcome.UNCHANGED;
            }
            outcome = UpsertOutcome.UPDATED;
        } else {
            history.save(DropoutHistory.of(programId, month, semester, enrolled, dropouts, label, actor, clock.instant()));
            outcome = UpsertOutcome.CREATED;
        }
        refresher.invalidate();
        return outcome;
    }
}

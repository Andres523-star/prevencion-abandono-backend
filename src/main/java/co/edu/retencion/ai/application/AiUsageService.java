package co.edu.retencion.ai.application;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.retencion.ai.domain.AiCall;
import co.edu.retencion.ai.domain.AiCallRepository;
import co.edu.retencion.common.time.TimeConfig;
import co.edu.retencion.settings.SettingsProvider;

/**
 * Presupuesto diario de tokens (configurable en caliente con {@code ai.daily-token-budget}),
 * contabilidad por día de Bogotá y bitácora de llamadas. El presupuesto es un tope blando: se revisa
 * antes de cada llamada, así que puede excederse a lo sumo por las llamadas en curso.
 */
@Service
public class AiUsageService {

    static final String BUDGET_KEY = "ai.daily-token-budget";
    static final long DEFAULT_BUDGET = 2_000_000L;

    private final JdbcTemplate jdbc;
    private final AiCallRepository calls;
    private final SettingsProvider settings;
    private final MeterRegistry meters;
    private final Clock clock;

    public AiUsageService(JdbcTemplate jdbc, AiCallRepository calls, SettingsProvider settings, MeterRegistry meters,
                          Clock clock) {
        this.jdbc = jdbc;
        this.calls = calls;
        this.settings = settings;
        this.meters = meters;
        this.clock = clock;
    }

    /** Consumo del día. */
    public record DailyUsage(LocalDate date, long inputTokens, long outputTokens, int calls, long budget) {

        public long total() {
            return inputTokens + outputTokens;
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    @Transactional(readOnly = true)
    public DailyUsage usageToday() {
        LocalDate day = today();
        List<DailyUsage> rows = jdbc.query(
                "SELECT input_tokens, output_tokens, calls FROM ai_token_usage WHERE usage_date = ?",
                (rs, i) -> new DailyUsage(day, rs.getLong(1), rs.getLong(2), rs.getInt(3), budget()),
                Date.valueOf(day));
        return rows.isEmpty() ? new DailyUsage(day, 0, 0, 0, budget()) : rows.get(0);
    }

    public long budget() {
        return settings.getLong(BUDGET_KEY, DEFAULT_BUDGET);
    }

    /** {@code true} si queda presupuesto para otra llamada. */
    public boolean hasBudget() {
        return usageToday().total() < budget();
    }

    /** Suma el consumo de una respuesta (en su propia transacción: se cuenta aunque el llamador falle). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String purpose, String model, long inputTokens, long outputTokens) {
        jdbc.update("""
                INSERT INTO ai_token_usage (usage_date, input_tokens, output_tokens, calls, updated_at)
                VALUES (?, ?, ?, 1, now())
                ON CONFLICT (usage_date) DO UPDATE SET
                    input_tokens = ai_token_usage.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = ai_token_usage.output_tokens + EXCLUDED.output_tokens,
                    calls = ai_token_usage.calls + 1,
                    updated_at = now()
                """, Date.valueOf(today()), inputTokens, outputTokens);
        meters.counter("ai.tokens", "direction", "input", "purpose", purpose, "model", model).increment(inputTokens);
        meters.counter("ai.tokens", "direction", "output", "purpose", purpose, "model", model).increment(outputTokens);
    }

    /** Bitácora de la llamada lógica (sin contenido). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logCall(String purpose, String model, String promptVersion, String payloadHash, String outcome,
                        int attempts, long inputTokens, long outputTokens, long latencyMs) {
        calls.save(AiCall.of(purpose, model, promptVersion, payloadHash, outcome, attempts, inputTokens, outputTokens,
                latencyMs, clock.instant()));
        meters.counter("ai.calls", "purpose", purpose, "outcome", outcome).increment();
    }
}

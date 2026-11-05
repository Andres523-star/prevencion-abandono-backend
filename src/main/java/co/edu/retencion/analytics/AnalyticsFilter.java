package co.edu.retencion.analytics;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;

import co.edu.retencion.common.error.DomainException;

/**
 * Filtros comunes de todos los gráficos: el frontend manda los mismos a cada endpoint para filtrar
 * todo a la vez al hacer clic en uno.
 *
 * <p>{@code from} y {@code to} eligen los periodos que se cruzan con el rango y, para los retiros, la
 * fecha del retiro. El semestre es el actual del estudiante (no se guarda el semestre histórico).
 */
public record AnalyticsFilter(UUID programId,
                              @Min(1) @Max(14) Integer semester,
                              @Min(1990) @Max(2100) Integer year,
                              UUID termId,
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              RiskBucket riskLevel) {

    /** Nivel de riesgo del último análisis; {@code SIN_ANALISIS} si el estudiante aún no tiene. */
    public enum RiskBucket {
        ALTO,
        MEDIO,
        BAJO,
        SIN_ANALISIS
    }

    public static AnalyticsFilter none() {
        return new AnalyticsFilter(null, null, null, null, null, null, null);
    }

    /** Rechaza rangos invertidos (400). */
    public AnalyticsFilter validated() {
        if (from != null && to != null && from.isAfter(to)) {
            throw DomainException.badRequest("El filtro 'from' no puede ser posterior a 'to'");
        }
        return this;
    }
}

package co.edu.retencion.justifications.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Evidencias de justificación ({@code app.justifications.*}).
 *
 * @param maxEvidenceSize        tamaño máximo por archivo
 * @param maxEvidencesPerRequest evidencias por justificación
 * @param downloadUrlTtl         vigencia de la URL prefirmada de descarga
 */
@ConfigurationProperties("app.justifications")
public record EvidenceProperties(@DefaultValue("5MB") DataSize maxEvidenceSize,
                                 @DefaultValue("5") int maxEvidencesPerRequest,
                                 @DefaultValue("5m") Duration downloadUrlTtl) {
}

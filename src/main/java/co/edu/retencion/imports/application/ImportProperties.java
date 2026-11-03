package co.edu.retencion.imports.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuración de la importación ({@code app.imports.*}).
 *
 * @param maxFileSize       tamaño máximo de archivo
 * @param syncMaxSize       hasta este tamaño se procesa en la misma petición; más grande, en cola
 * @param staleAfter        tras cuánto un trabajo "en proceso" se considera huérfano y se reencola
 * @param aliases           alias extra por campo, p. ej. {@code documento: [num_identificacion]}
 *                          (el mapeo de los datos reales se ajusta aquí sin tocar código)
 */
@ConfigurationProperties("app.imports")
public record ImportProperties(
        @DefaultValue("20MB") DataSize maxFileSize,
        @DefaultValue("256KB") DataSize syncMaxSize,
        @DefaultValue("30m") Duration staleAfter,
        @DefaultValue Map<String, List<String>> aliases) {
}

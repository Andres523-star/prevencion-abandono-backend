package co.edu.retencion.ai;

/**
 * Trazabilidad de una llamada a la IA (se guarda con cada análisis o plan).
 *
 * @param model            modelo que respondió (o el solicitado si no hubo respuesta)
 * @param promptVersion    versión del prompt
 * @param payloadHash      SHA-256 del payload enviado
 * @param inputTokens      tokens de entrada (suma de intentos)
 * @param outputTokens     tokens de salida (suma de intentos)
 * @param attempts         intentos realizados (reintento por salida inválida)
 * @param failure          motivo del fallo, o {@code null} si hubo salida válida
 * @param sensitiveContext el payload incluía señales sensibles
 */
public record AiCallInfo(String model, String promptVersion, String payloadHash, long inputTokens, long outputTokens,
                         int attempts, AiFailure failure, boolean sensitiveContext) {

    public boolean succeeded() {
        return failure == null;
    }
}

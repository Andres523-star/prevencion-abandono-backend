package co.edu.retencion.audit.domain;

/**
 * Resultado de verificar la integridad de la cadena de auditoría.
 *
 * @param checked        filas revisadas
 * @param valid          {@code true} si toda la cadena es consistente
 * @param firstBrokenSeq primera posición inconsistente, o {@code null}
 * @param problem        descripción del primer problema, o {@code null}
 */
public record ChainVerification(long checked, boolean valid, Long firstBrokenSeq, String problem) {

    public static ChainVerification ok(long checked) {
        return new ChainVerification(checked, true, null, null);
    }

    public static ChainVerification broken(long checked, long seq, String problem) {
        return new ChainVerification(checked, false, seq, problem);
    }
}

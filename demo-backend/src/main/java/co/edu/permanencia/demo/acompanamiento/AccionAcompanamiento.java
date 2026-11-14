package co.edu.permanencia.demo.acompanamiento;

/**
 * Acción del historial de acompañamiento de un estudiante.
 *
 * <p>Funciona como una historia clínica: cada cita, taller o llamada queda registrada con su
 * estado de cumplimiento para que el docente pueda marcarla.</p>
 */
public record AccionAcompanamiento(
        int id,
        int estudianteId,
        String fecha,
        String titulo,
        String descripcion,
        String responsable,
        String estado
) {
}

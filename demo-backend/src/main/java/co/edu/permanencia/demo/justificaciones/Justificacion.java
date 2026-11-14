package co.edu.permanencia.demo.justificaciones;

/**
 * Justificación de una falta enviada por el estudiante.
 *
 * <p>La demo no envía correos reales: registra el radicado y guarda el nombre de la evidencia para
 * que el flujo completo pueda demostrarse. El campo {@code evidencia} llega como data URL o nombre
 * de archivo desde el formulario.</p>
 */
public record Justificacion(
        int id,
        int estudianteId,
        String estudiante,
        String materia,
        int semestre,
        String grupo,
        String fechaFalta,
        String motivo,
        String descripcion,
        String evidencia,
        String correoDestino,
        String estado,
        String radicado,
        String creadaEn
) {
}

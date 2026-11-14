package co.edu.permanencia.demo.justificaciones;

/** Datos que envía el estudiante para justificar una falta. */
public record JustificacionRequest(
        String materia,
        Integer semestre,
        String grupo,
        String fechaFalta,
        String motivo,
        String descripcion,
        String evidencia,
        String correoDestino
) {
}

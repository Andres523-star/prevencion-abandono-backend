package co.edu.permanencia.demo.chat;

/** Resumen de una conversación para la lista lateral del chat. */
public record Conversacion(
        int estudianteId,
        String nombre,
        String apellido,
        String carrera,
        int semestre,
        String categoria,
        String riesgoActual,
        String ultimoMensaje,
        String ultimaFecha,
        long noLeidos
) {
}

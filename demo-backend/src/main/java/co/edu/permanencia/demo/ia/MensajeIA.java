package co.edu.permanencia.demo.ia;

/**
 * Turno guardado del historial de IA.
 *
 * <p>Cada usuario tiene su propio hilo, identificado por el correo de la cuenta. El autor puede ser
 * {@code YO} (la persona) o {@code IA} (el asistente). Los estudiantes ven únicamente su hilo: la
 * conversación es confidencial y no se comparte con profesores ni con otros roles.</p>
 */
public record MensajeIA(int id, String autor, String texto, String fecha) {
}

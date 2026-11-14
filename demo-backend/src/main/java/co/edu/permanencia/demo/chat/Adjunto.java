package co.edu.permanencia.demo.chat;

/** Archivo adjunto guardado en memoria. */
public record Adjunto(byte[] bytes, String nombre, String mime) {
}

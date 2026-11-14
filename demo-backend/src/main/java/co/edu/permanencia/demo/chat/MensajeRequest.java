package co.edu.permanencia.demo.chat;

/** Cuerpo para enviar un mensaje. Si hay archivo, viaja en Base64 para no montar multipart. */
public record MensajeRequest(
        String texto,
        String tipo,
        String nombreArchivo,
        String mime,
        String archivoBase64
) {
}

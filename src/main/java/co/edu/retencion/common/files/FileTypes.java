package co.edu.retencion.common.files;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import co.edu.retencion.common.error.DomainException;

/**
 * Detección del tipo real de archivo por <em>magic bytes</em> (nunca por la extensión ni por el
 * Content-Type que manda el cliente) y verificación de que la extensión declarada coincida.
 */
public final class FileTypes {

    /** Tipos admitidos para evidencias de justificación. */
    public enum Kind {
        PDF("application/pdf", Set.of("pdf")),
        JPEG("image/jpeg", Set.of("jpg", "jpeg")),
        PNG("image/png", Set.of("png"));

        private final String mediaType;
        private final Set<String> extensions;

        Kind(String mediaType, Set<String> extensions) {
            this.mediaType = mediaType;
            this.extensions = extensions;
        }

        public String mediaType() {
            return mediaType;
        }

        public String defaultExtension() {
            return extensions.iterator().next();
        }

        boolean acceptsExtension(String extension) {
            return extensions.contains(extension);
        }
    }

    private FileTypes() {
    }

    public static Optional<Kind> detect(byte[] content) {
        if (startsWith(content, 0x25, 0x50, 0x44, 0x46, 0x2D)) { // %PDF-
            return Optional.of(Kind.PDF);
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(Kind.JPEG);
        }
        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(Kind.PNG);
        }
        return Optional.empty();
    }

    /**
     * Valida tamaño, tipo real y extensión.
     *
     * @return el tipo detectado
     * @throws DomainException si el archivo está vacío, es muy grande, no es de un tipo permitido o
     *                         su extensión no corresponde con su contenido
     */
    public static Kind requireAllowed(String fileName, byte[] content, long maxBytes) {
        if (content.length == 0) {
            throw DomainException.badRequest("El archivo está vacío");
        }
        if (content.length > maxBytes) {
            throw DomainException.badRequest("El archivo supera el tamaño máximo de " + (maxBytes / (1024 * 1024)) + " MB");
        }
        Kind kind = detect(content)
                .orElseThrow(() -> DomainException.badRequest("Tipo de archivo no permitido: use PDF, JPG o PNG"));
        String extension = extensionOf(fileName);
        if (extension != null && !kind.acceptsExtension(extension)) {
            throw DomainException.badRequest("La extensión del archivo no corresponde con su contenido");
        }
        return kind;
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] content, int... signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}

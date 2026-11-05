package co.edu.retencion.chat.domain;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import co.edu.retencion.common.error.DomainException;

/**
 * Tipos de adjunto del chat por <em>magic bytes</em> (servicio de dominio puro). Admite PDF, Word
 * .docx sin macros y audio (webm/opus, ogg, mp3, m4a/mp4). Rechaza .doc (formato binario OLE, vector
 * clásico de macros), .docm y cualquier .docx que declare macros, y toda extensión que no coincida
 * con el contenido real.
 */
public final class ChatFileTypes {

    static final int MAX_ZIP_ENTRIES = 2000;
    static final int MAX_CONTENT_TYPES_BYTES = 256 * 1024;

    private ChatFileTypes() {
    }

    /** Tipo admitido. */
    public enum Kind {
        PDF("application/pdf", Set.of("pdf"), ChatMessage.Type.DOCUMENTO),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", Set.of("docx"),
                ChatMessage.Type.DOCUMENTO),
        WEBM("audio/webm", Set.of("webm", "weba"), ChatMessage.Type.AUDIO),
        OGG("audio/ogg", Set.of("ogg", "oga", "opus"), ChatMessage.Type.AUDIO),
        MP3("audio/mpeg", Set.of("mp3"), ChatMessage.Type.AUDIO),
        M4A("audio/mp4", Set.of("m4a", "mp4"), ChatMessage.Type.AUDIO);

        private final String mediaType;
        private final Set<String> extensions;
        private final ChatMessage.Type messageType;

        Kind(String mediaType, Set<String> extensions, ChatMessage.Type messageType) {
            this.mediaType = mediaType;
            this.extensions = extensions;
            this.messageType = messageType;
        }

        public String mediaType() {
            return mediaType;
        }

        public ChatMessage.Type messageType() {
            return messageType;
        }

        public String extension() {
            return extensions.iterator().next();
        }

        public boolean accepts(String extension) {
            return extensions.contains(extension);
        }
    }

    /**
     * Valida extensión, tamaño y contenido.
     *
     * @throws DomainException 400 con el motivo
     */
    public static Kind requireAllowed(String fileName, byte[] content, long maxBytes) {
        String ext = extensionOf(fileName);
        if (ext == null) {
            throw DomainException.badRequest("El archivo debe tener extensión");
        }
        if (Set.of("doc", "docm", "dotm", "dot", "xlsm", "pptm").contains(ext)) {
            throw DomainException.badRequest("Formato no permitido (.doc y documentos con macros); use .docx o PDF");
        }
        if (content.length == 0) {
            throw DomainException.badRequest("El archivo está vacío");
        }
        if (content.length > maxBytes) {
            throw DomainException.badRequest("El archivo supera el tamaño máximo de " + (maxBytes / (1024 * 1024)) + " MB");
        }
        Kind kind = detect(content);
        if (!kind.accepts(ext)) {
            throw DomainException.badRequest("La extensión del archivo no corresponde con su contenido");
        }
        return kind;
    }

    static Kind detect(byte[] c) {
        if (starts(c, 0, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
            return Kind.PDF;
        }
        if (starts(c, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            throw DomainException.badRequest("Formato .doc no permitido; use .docx o PDF");
        }
        if (starts(c, 0, 0x50, 0x4B, 0x03, 0x04)) {
            return docx(c);
        }
        if (starts(c, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return Kind.WEBM;
        }
        if (starts(c, 0, 0x4F, 0x67, 0x67, 0x53)) {
            return Kind.OGG;
        }
        if (starts(c, 0, 0x49, 0x44, 0x33) || (c.length > 1 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xE0) == 0xE0)) {
            return Kind.MP3;
        }
        if (starts(c, 4, 0x66, 0x74, 0x79, 0x70)) {
            return Kind.M4A;
        }
        throw DomainException.badRequest("Tipo de archivo no permitido: use PDF, Word (.docx) o audio");
    }

    /** Un .docx es un ZIP con [Content_Types].xml y word/document.xml, sin proyecto VBA ni tipo con macros. */
    private static Kind docx(byte[] content) {
        boolean contentTypes = false;
        boolean document = false;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw DomainException.badRequest("Documento con demasiadas partes");
                }
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin") || name.endsWith("vbadata.xml")) {
                    throw DomainException.badRequest("El documento contiene macros; no está permitido");
                }
                if (name.equals("word/document.xml")) {
                    document = true;
                }
                if (name.equals("[content_types].xml")) {
                    contentTypes = true;
                    byte[] xml = zip.readNBytes(MAX_CONTENT_TYPES_BYTES);
                    if (new String(xml, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("macroenabled")) {
                        throw DomainException.badRequest("El documento contiene macros; no está permitido");
                    }
                }
            }
        } catch (IOException ex) {
            throw DomainException.badRequest("Documento dañado o ilegible");
        }
        if (!contentTypes || !document) {
            throw DomainException.badRequest("Solo se admiten documentos Word (.docx) o PDF");
        }
        return Kind.DOCX;
    }

    /** Nombre seguro para mostrar: sin ruta, sin caracteres de control, máximo 120 caracteres. */
    public static String safeName(String fileName) {
        String name = fileName == null ? "archivo" : fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").strip();
        if (name.isEmpty()) {
            name = "archivo";
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? null : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean starts(byte[] content, int offset, int... signature) {
        if (content.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}

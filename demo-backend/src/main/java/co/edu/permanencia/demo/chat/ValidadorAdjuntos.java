package co.edu.permanencia.demo.chat;

import co.edu.permanencia.demo.common.ApiException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Valida los adjuntos del chat por su contenido real (<i>magic bytes</i>), con las mismas reglas
 * del chat del backend principal.
 *
 * <p>El tipo que declara el navegador y la extensión se pueden falsear, así que el tipo MIME con
 * el que luego se sirve el archivo sale de aquí, no del cliente. Se aceptan PDF, Word .docx y
 * audio (webm, ogg, mp3, m4a, wav). Se rechazan .doc (formato OLE) y los .docx con macros
 * ({@code vbaProject.bin} o tipo <i>macroEnabled</i>), que son la vía típica de malware.</p>
 */
final class ValidadorAdjuntos {

    static final String PDF = "application/pdf";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Set<String> EXT_AUDIO = Set.of("webm", "ogg", "oga", "opus", "mp3", "m4a", "mp4", "wav");
    /** Tope de entradas del ZIP que se inspeccionan (evita un ZIP con miles de entradas). */
    private static final int MAX_ENTRADAS_ZIP = 2_000;

    private ValidadorAdjuntos() {
    }

    /** Resultado de la validación: tipo detectado y nombre saneado. */
    record Adjunto(String mime, String nombre) {
    }

    static Adjunto validar(byte[] bytes, String nombreDeclarado) {
        String nombre = sanear(nombreDeclarado);
        String extension = extension(nombre);
        String mime = detectar(bytes);
        if (mime == null) {
            throw ApiException.badRequest("Solo se permiten PDF, Word (.docx) o audio");
        }
        boolean coherente = switch (mime) {
            case PDF -> extension.equals("pdf");
            case DOCX -> extension.equals("docx");
            default -> EXT_AUDIO.contains(extension);
        };
        if (!coherente) {
            throw ApiException.badRequest("La extensión del archivo no coincide con su contenido");
        }
        return new Adjunto(mime, nombre);
    }

    /** Tipo real según los primeros bytes, o null si no es un tipo permitido. */
    static String detectar(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        if (empieza(b, 0, "%PDF-")) {
            return PDF;
        }
        if ((b[0] & 0xFF) == 0x50 && (b[1] & 0xFF) == 0x4B && (b[2] & 0xFF) == 0x03 && (b[3] & 0xFF) == 0x04) {
            return docxSinMacros(b) ? DOCX : null;
        }
        if ((b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            return "audio/webm";
        }
        if (empieza(b, 0, "OggS")) {
            return "audio/ogg";
        }
        if (empieza(b, 0, "ID3") || ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0)) {
            return "audio/mpeg";
        }
        if (empieza(b, 4, "ftyp")) {
            return "audio/mp4";
        }
        if (empieza(b, 0, "RIFF") && empieza(b, 8, "WAVE")) {
            return "audio/wav";
        }
        return null;
    }

    private static boolean docxSinMacros(byte[] bytes) {
        boolean documento = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entrada;
            int vistas = 0;
            while ((entrada = zip.getNextEntry()) != null && vistas++ < MAX_ENTRADAS_ZIP) {
                String ruta = entrada.getName().toLowerCase(Locale.ROOT);
                if (ruta.endsWith("vbaproject.bin")) {
                    return false;
                }
                if (ruta.equals("word/document.xml")) {
                    documento = true;
                }
                if (ruta.equals("[content_types].xml")) {
                    String tipos = new String(zip.readNBytes(256 * 1024), StandardCharsets.UTF_8);
                    if (tipos.toLowerCase(Locale.ROOT).contains("macroenabled")) {
                        return false;
                    }
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            return false;
        }
        return documento;
    }

    private static boolean empieza(byte[] b, int desde, String firma) {
        byte[] f = firma.getBytes(StandardCharsets.US_ASCII);
        if (b.length < desde + f.length) {
            return false;
        }
        for (int i = 0; i < f.length; i++) {
            if (b[desde + i] != f[i]) {
                return false;
            }
        }
        return true;
    }

    /** Nombre sin rutas ni caracteres de control, máximo 120 caracteres. */
    static String sanear(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return "archivo";
        }
        String base = nombre.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^\\p{L}\\p{N} ._()-]", "_").trim();
        if (base.isBlank() || base.chars().allMatch(c -> c == '.')) {
            return "archivo";
        }
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static String extension(String nombre) {
        int punto = nombre.lastIndexOf('.');
        return punto < 0 ? "" : nombre.substring(punto + 1).toLowerCase(Locale.ROOT);
    }
}

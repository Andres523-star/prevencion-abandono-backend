package co.edu.retencion.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Archivos de prueba para los adjuntos del chat (documentos Word mínimos, audio y EICAR). */
public final class OfficeFiles {

    public static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] OLE_DOC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
            (byte) 0xE1, 0, 0, 0, 0};
    public static final byte[] OGG = {'O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0, 0, 0};
    public static final byte[] MP3 = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0};
    public static final byte[] EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
            .getBytes(StandardCharsets.US_ASCII);

    private OfficeFiles() {
    }

    public static byte[] docx() {
        return zip(false, false);
    }

    /** .docx con proyecto VBA (macros). */
    public static byte[] docxWithMacros() {
        return zip(true, false);
    }

    /** Tipo de contenido macroEnabled (lo que produce Word al guardar .docm). */
    public static byte[] docmContentType() {
        return zip(false, true);
    }

    private static byte[] zip(boolean vba, boolean macroType) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            String type = macroType ? "application/vnd.ms-word.document.macroEnabled.main+xml"
                    : "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
            zip.write(("<Types><Override PartName=\"/word/document.xml\" ContentType=\"" + type + "\"/></Types>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (vba) {
                zip.putNextEntry(new ZipEntry("word/vbaProject.bin"));
                zip.write(new byte[] {1, 2, 3});
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

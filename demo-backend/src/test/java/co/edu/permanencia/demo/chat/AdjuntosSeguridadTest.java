package co.edu.permanencia.demo.chat;

import co.edu.permanencia.demo.common.ApiException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validación de adjuntos por contenido y enlaces de descarga firmados. */
class AdjuntosSeguridadTest {

    private static byte[] pdf() {
        return "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] zip(String... entradas) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(salida)) {
            for (String entrada : entradas) {
                String[] partes = entrada.split("=", 2);
                zip.putNextEntry(new ZipEntry(partes[0]));
                zip.write((partes.length > 1 ? partes[1] : "x").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return salida.toByteArray();
    }

    @Test
    void aceptaPdfDocxYNotasDeVoz() throws IOException {
        assertThat(ValidadorAdjuntos.validar(pdf(), "acta.pdf").mime()).isEqualTo("application/pdf");
        byte[] docx = zip("[Content_Types].xml=<Types/>", "word/document.xml=<w:document/>");
        assertThat(ValidadorAdjuntos.validar(docx, "plan.docx").mime()).isEqualTo(ValidadorAdjuntos.DOCX);
        byte[] webm = new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThat(ValidadorAdjuntos.validar(webm, "nota-de-voz-1.webm").mime()).isEqualTo("audio/webm");
        byte[] m4a = "\0\0\0\u0020ftypM4A \0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(ValidadorAdjuntos.validar(m4a, "nota-de-voz-2.m4a").mime()).isEqualTo("audio/mp4");
    }

    @Test
    void rechazaHtmlDisfrazadoDePdf() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ValidadorAdjuntos.validar(html, "acta.pdf")).isInstanceOf(ApiException.class);
    }

    @Test
    void rechazaWordConMacrosYFormatoDocAntiguo() throws IOException {
        byte[] conMacro = zip("word/document.xml=<w:document/>", "word/vbaProject.bin=MZ");
        assertThatThrownBy(() -> ValidadorAdjuntos.validar(conMacro, "plan.docx")).isInstanceOf(ApiException.class);
        byte[] macroEnabled = zip("[Content_Types].xml=application/vnd.ms-word.document.macroEnabled.main+xml",
                "word/document.xml=<w:document/>");
        assertThatThrownBy(() -> ValidadorAdjuntos.validar(macroEnabled, "plan.docx")).isInstanceOf(ApiException.class);
        byte[] ole = new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
                (byte) 0xE1, 0, 0, 0, 0, 0};
        assertThatThrownBy(() -> ValidadorAdjuntos.validar(ole, "viejo.doc")).isInstanceOf(ApiException.class);
    }

    @Test
    void rechazaExtensionQueNoCoincideConElContenido() {
        assertThatThrownBy(() -> ValidadorAdjuntos.validar(pdf(), "audio.mp3"))
                .hasMessageContaining("no coincide");
    }

    @Test
    void saneaElNombreDelArchivo() {
        assertThat(ValidadorAdjuntos.sanear("../../etc/pas\"swd<script>.pdf")).isEqualTo("pas_swd_script_.pdf");
        assertThat(ValidadorAdjuntos.sanear("C:\\Users\\x\\informe final.pdf")).isEqualTo("informe final.pdf");
        assertThat(ValidadorAdjuntos.sanear(null)).isEqualTo("archivo");
    }

    @Test
    void elEnlaceFirmadoSoloSirveParaEseArchivoYVence() {
        Instant inicio = Instant.parse("2026-11-19T15:00:00Z");
        EnlacesAdjuntos ahora = new EnlacesAdjuntos(new byte[32], Clock.fixed(inicio, ZoneOffset.UTC), Duration.ofMinutes(60));
        String firma = ahora.firmar("archivo-1");

        assertThat(ahora.valida("archivo-1", firma)).isTrue();
        assertThat(ahora.valida("archivo-2", firma)).as("otro archivo").isFalse();
        assertThat(ahora.valida("archivo-1", null)).isFalse();
        assertThat(ahora.valida("archivo-1", firma.substring(0, firma.length() - 1) + "x")).isFalse();
        String[] partes = firma.split("\\.");
        assertThat(ahora.valida("archivo-1", (Long.parseLong(partes[0]) + 3600) + "." + partes[1]))
                .as("alargar el vencimiento invalida la firma").isFalse();

        EnlacesAdjuntos despues = new EnlacesAdjuntos(new byte[32],
                Clock.fixed(inicio.plus(Duration.ofMinutes(121)), ZoneOffset.UTC), Duration.ofMinutes(60));
        assertThat(despues.valida("archivo-1", firma)).as("vencido").isFalse();
    }

    @Test
    void laFirmaNoCambiaEntreRecargasParaQueElAudioNoSeReinicie() {
        Instant inicio = Instant.parse("2026-11-19T15:10:00Z");
        String primera = new EnlacesAdjuntos(new byte[32], Clock.fixed(inicio, ZoneOffset.UTC), Duration.ofMinutes(60))
                .firmar("nota-1");
        String recarga = new EnlacesAdjuntos(new byte[32],
                Clock.fixed(inicio.plusSeconds(40), ZoneOffset.UTC), Duration.ofMinutes(60)).firmar("nota-1");
        assertThat(recarga).isEqualTo(primera);
        long vence = Long.parseLong(primera.split("\\.")[0]);
        assertThat(vence - inicio.getEpochSecond()).as("dura entre 1 y 2 horas").isBetween(3600L, 7200L);
    }
}

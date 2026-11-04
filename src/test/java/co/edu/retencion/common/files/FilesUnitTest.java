package co.edu.retencion.common.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.support.MutableClock;

/** Validación de archivos por magic bytes, adaptador de clamd y almacenamiento local firmado. */
class FilesUnitTest {

    static final byte[] PDF = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0};

    @Test
    void detectsTypesByContentNotByName() {
        assertThat(FileTypes.requireAllowed("soporte.pdf", PDF, 1024)).isEqualTo(FileTypes.Kind.PDF);
        assertThat(FileTypes.requireAllowed("foto.JPG", JPEG, 1024)).isEqualTo(FileTypes.Kind.JPEG);
        assertThat(FileTypes.requireAllowed("captura", PNG, 1024)).isEqualTo(FileTypes.Kind.PNG);
        // Extensión engañosa, tipo no permitido, vacío y demasiado grande.
        assertThatThrownBy(() -> FileTypes.requireAllowed("soporte.pdf", PNG, 1024)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> FileTypes.requireAllowed("doc.pdf", "MZ\0\0".getBytes(StandardCharsets.ISO_8859_1), 1024))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> FileTypes.requireAllowed("a.pdf", new byte[0], 1024)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> FileTypes.requireAllowed("a.pdf", PDF, 4)).isInstanceOf(DomainException.class);
    }

    @Test
    void signatureScannerDetectsEicar() {
        String eicar = SignatureScanner.EICAR;
        assertThat(new SignatureScanner().scan(("%PDF-1.4 " + eicar).getBytes(StandardCharsets.US_ASCII)).verdict())
                .isEqualTo(MalwareScanner.Verdict.INFECTED);
        assertThat(new SignatureScanner().scan(PDF).verdict()).isEqualTo(MalwareScanner.Verdict.CLEAN);
    }

    @Test
    void clamAvAdapterSpeaksInstreamAndMapsVerdicts() throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread clamd = fakeClamd(server, received, "stream: Eicar-Test-Signature FOUND");
            ClamAvScanner scanner = new ClamAvScanner("localhost", server.getLocalPort(), Duration.ofSeconds(5));
            MalwareScanner.ScanResult result = scanner.scan(PDF);
            clamd.join(5000);
            assertThat(result.verdict()).isEqualTo(MalwareScanner.Verdict.INFECTED);
            assertThat(result.signature()).isEqualTo("Eicar-Test-Signature");
            assertThat(received.get()).isEqualTo(PDF);
        }
        try (ServerSocket server = new ServerSocket(0)) {
            Thread clamd = fakeClamd(server, received, "stream: OK");
            MalwareScanner.ScanResult result = new ClamAvScanner("localhost", server.getLocalPort(),
                    Duration.ofSeconds(5)).scan(PDF);
            clamd.join(5000);
            assertThat(result.verdict()).isEqualTo(MalwareScanner.Verdict.CLEAN);
        }
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }
        assertThat(new ClamAvScanner("localhost", closedPort, Duration.ofSeconds(1)).scan(PDF).verdict())
                .isEqualTo(MalwareScanner.Verdict.ERROR);
    }

    @Test
    void localStorageIssuesExpiringSignedUrls(@TempDir Path dir) {
        MutableClock clock = new MutableClock();
        LocalObjectStorage storage = new LocalObjectStorage(dir, "http://localhost:8080/", clock);
        storage.put("evidencias/abc12345-pdf", PDF, "application/pdf");
        assertThat(storage.get("evidencias/abc12345-pdf")).isEqualTo(PDF);

        String url = storage.presignedDownloadUrl("evidencias/abc12345-pdf", Duration.ofMinutes(5), "evidencia.pdf")
                .toString();
        assertThat(url).startsWith("http://localhost:8080/api/v1/files/");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThat(storage.verify(token)).get().extracting(LocalObjectStorage.SignedDownload::key)
                .isEqualTo("evidencias/abc12345-pdf");
        assertThat(storage.verify(token.substring(0, token.length() - 2) + "xx")).isEmpty();
        clock.advance(Duration.ofMinutes(6));
        assertThat(storage.verify(token)).isEmpty();

        assertThatThrownBy(() -> storage.put("../../etc/passwd", PDF, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        storage.delete("evidencias/abc12345-pdf");
    }

    /** clamd simulado: lee el protocolo zINSTREAM y responde con {@code reply}. */
    private static Thread fakeClamd(ServerSocket server, AtomicReference<byte[]> received, String reply) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] command = new byte[10];
                in.readFully(command);
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                int length;
                while ((length = in.readInt()) > 0) {
                    byte[] chunk = new byte[length];
                    in.readFully(chunk);
                    data.write(chunk);
                }
                received.set(data.toByteArray());
                socket.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (IOException ignored) {
                // la prueba falla por el veredicto
            }
        });
        thread.start();
        return thread;
    }
}

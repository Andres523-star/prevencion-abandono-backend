package co.edu.retencion.common.files;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Patrón estructural: Adapter.</b> Adapta el protocolo TCP de clamd (comando {@code INSTREAM})
 * al puerto {@link MalwareScanner}: se envía el archivo en bloques con prefijo de longitud y clamd
 * responde {@code stream: OK} o {@code stream: <firma> FOUND}.
 */
public class ClamAvScanner implements MalwareScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);
    private static final int CHUNK = 64 * 1024;

    private final String host;
    private final int port;
    private final Duration timeout;

    public ClamAvScanner(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public ScanResult scan(byte[] content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; offset += CHUNK) {
                int length = Math.min(CHUNK, content.length - offset);
                out.writeInt(length);
                out.write(content, offset, length);
            }
            out.writeInt(0);
            out.flush();
            String response = readResponse(socket.getInputStream());
            if (response.endsWith("OK")) {
                return ScanResult.clean();
            }
            if (response.endsWith("FOUND")) {
                String signature = response.replace("stream:", "").replace("FOUND", "").trim();
                return new ScanResult(Verdict.INFECTED, signature);
            }
            log.warn("Respuesta inesperada de clamd");
            return ScanResult.error();
        } catch (IOException ex) {
            log.warn("ClamAV no disponible: {}", ex.getClass().getSimpleName());
            return ScanResult.error();
        }
    }

    private static String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            buffer.write(b);
        }
        return buffer.toString(StandardCharsets.US_ASCII).trim();
    }
}

package co.edu.retencion.common.files;

import java.nio.charset.StandardCharsets;

/**
 * Escáner mínimo para desarrollo y pruebas: solo detecta la cadena de prueba estándar EICAR. NO es
 * un antivirus; en producción se usa {@link ClamAvScanner}.
 */
public class SignatureScanner implements MalwareScanner {

    /** Firma EICAR (archivo de prueba inofensivo reconocido por todos los antivirus). */
    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Override
    public ScanResult scan(byte[] content) {
        String text = new String(content, StandardCharsets.ISO_8859_1);
        return text.contains(EICAR) ? new ScanResult(Verdict.INFECTED, "Eicar-Test-Signature") : ScanResult.clean();
    }
}

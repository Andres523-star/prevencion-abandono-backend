package co.edu.retencion.audit.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cálculo del hash encadenado: {@code hash_n = SHA-256(hash_(n-1) || canónico(entrada_n))}.
 *
 * <p>Si alguien altera, borra o inserta una fila (aun saltándose el trigger con permisos de
 * superusuario), el hash recalculado deja de coincidir a partir de ese punto.
 *
 * <p>La forma canónica antepone la longitud a cada campo ({@code len:valor;}) para que no haya
 * ambigüedad entre, por ejemplo, ("ab","c") y ("a","bc").
 */
public final class HashChain {

    /** Hash "génesis" con el que arranca la cadena. */
    public static final String GENESIS = "0".repeat(64);

    private HashChain() {
    }

    public static String compute(String prevHash, long seq, AuditEntry e) {
        StringBuilder canonical = new StringBuilder(256);
        field(canonical, prevHash);
        field(canonical, Long.toString(seq));
        field(canonical, e.id().toString());
        field(canonical, e.occurredAt().toString());
        field(canonical, e.actorUserId() == null ? null : e.actorUserId().toString());
        field(canonical, e.actorRoles());
        field(canonical, e.action());
        field(canonical, e.entityType());
        field(canonical, e.entityId());
        field(canonical, e.outcome().name());
        field(canonical, e.ipAddress());
        field(canonical, e.requestId());
        field(canonical, e.reason());
        for (Map.Entry<String, String> d : new TreeMap<>(e.details()).entrySet()) {
            field(canonical, d.getKey());
            field(canonical, d.getValue());
        }
        return sha256(canonical.toString());
    }

    private static void field(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("-1:;");
        } else {
            sb.append(value.length()).append(':').append(value).append(';');
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

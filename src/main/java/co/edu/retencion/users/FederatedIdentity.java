package co.edu.retencion.users;

/**
 * Identidad validada que llega del proveedor de autenticación externo (Supabase Auth).
 *
 * @param issuer      emisor del token (URL del proyecto de Supabase + {@code /auth/v1})
 * @param subject     id inmutable del usuario en el proveedor ({@code sub})
 * @param email       correo verificado y normalizado
 * @param displayName nombre para mostrar
 */
public record FederatedIdentity(String issuer, String subject, String email, String displayName) {

    @Override
    public String toString() {
        // Nunca imprimir el correo ni el nombre en logs.
        return "FederatedIdentity[issuer=" + issuer + ", subject=" + subject + "]";
    }
}

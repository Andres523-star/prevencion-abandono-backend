package co.edu.retencion.users;

/** Estado de la cuenta. Un usuario DISABLED no puede abrir ni renovar sesiones. */
public enum UserStatus {
    ACTIVE,
    DISABLED
}

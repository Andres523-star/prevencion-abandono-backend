package co.edu.retencion.users;

/** Origen de la identidad del usuario. */
public enum AuthType {
    /** Cuenta gestionada por Supabase Auth (correo, enlace mágico, Google, Microsoft...). */
    SUPABASE,
    /** Cuenta local de emergencia (break-glass). */
    LOCAL
}

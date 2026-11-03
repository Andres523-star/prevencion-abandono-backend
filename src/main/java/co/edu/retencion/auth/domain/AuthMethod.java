package co.edu.retencion.auth.domain;

/** Cómo se abrió la sesión. */
public enum AuthMethod {
    /** Cuenta de Supabase Auth canjeada por una sesión de la plataforma. */
    SUPABASE,
    /** Cuenta local de emergencia (contraseña + TOTP). */
    LOCAL
}

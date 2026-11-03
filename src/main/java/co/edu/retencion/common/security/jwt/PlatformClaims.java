package co.edu.retencion.common.security.jwt;

/**
 * Nombres de los claims propios del JWT de la plataforma. Centralizados para que el emisor
 * (módulo auth) y el validador (common) no se desalineen.
 */
public final class PlatformClaims {

    /** Lista de roles asignados en la plataforma. */
    public static final String ROLES = "roles";
    /** Programas en alcance (UUID como texto). */
    public static final String PROGRAMS = "programs";
    /** {@code true} si la sesión se abrió con MFA. */
    public static final String MFA = "mfa";
    /** Id de sesión (familia de refresh tokens). */
    public static final String SESSION_ID = "sid";
    /** Método de autenticación: SUPABASE o LOCAL (break-glass). */
    public static final String AUTH_METHOD = "auth_method";

    private PlatformClaims() {
    }
}

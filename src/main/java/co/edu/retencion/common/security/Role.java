package co.edu.retencion.common.security;

/**
 * Roles de la plataforma. Se asignan dentro de la plataforma (tabla user_roles), nunca a partir
 * de grupos arbitrarios que vengan en el token del proveedor de identidad.
 *
 * <p>{@link #requiresMfa()} es una regla no negociable del proyecto: los roles con acceso a
 * datos sensibles exigen autenticación multifactor.
 */
public enum Role {

    ADMIN(true),
    CONSEJERO(true),
    PSICOLOGO(true),
    COORDINADOR(true),
    DOCENTE(false),
    ESTUDIANTE(false);

    private final boolean requiresMfa;

    Role(boolean requiresMfa) {
        this.requiresMfa = requiresMfa;
    }

    public boolean requiresMfa() {
        return requiresMfa;
    }

    /** Nombre de la autoridad de Spring Security, p. ej. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}

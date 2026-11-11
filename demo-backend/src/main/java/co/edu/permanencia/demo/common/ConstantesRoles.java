package co.edu.permanencia.demo.common;

public final class ConstantesRoles {
    private ConstantesRoles() {}

    public static final String ROL_ESTUDIANTE = "ESTUDIANTE";
    public static final String ROL_PROFESOR = "PROFESOR";
    public static final String ROL_JEFE_PROGRAMA = "JEFE_PROGRAMA";
    public static final String ROL_COORDINADOR_SEDE = "COORDINADOR_SEDE";
    public static final String ROL_DECANO = "DECANO";
    public static final String ROL_RECTOR = "RECTOR";

    public static boolean esRolAdministrativo(String rol) {
        return ROL_COORDINADOR_SEDE.equals(rol) || ROL_DECANO.equals(rol) || ROL_RECTOR.equals(rol);
    }
}

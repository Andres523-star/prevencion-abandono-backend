package co.edu.permanencia.demo.auth;

/**
 * Usuario de la demo. No contiene contraseña ni datos sensibles.
 *
 * <p>Las cuentas institucionales usan programa, sede, facultad y semestres para delimitar su
 * alcance. La cuenta de estudiante guarda además el id del estudiante demo al que representa.</p>
 */
public record Usuario(int id, String nombre, String apellido, String email, String rol,
        String programa, String sede, String semestres, String facultad, Integer estudianteId) {

    public String nombreCompleto() {
        return nombre + " " + apellido;
    }
}

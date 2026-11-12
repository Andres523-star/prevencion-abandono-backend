package co.edu.permanencia.demo.auth;

/** Datos que recibe el frontend al iniciar sesión o registrarse. */
public record SesionResponse(int id, String nombre, String apellido, String email, String rol,
        String programa, String sede, String facultad, Integer estudianteId, String token) {
}

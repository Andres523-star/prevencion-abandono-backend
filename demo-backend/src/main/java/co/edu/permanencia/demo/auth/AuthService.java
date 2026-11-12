package co.edu.permanencia.demo.auth;

import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Autenticación de la demo.
 *
 * <p>Las cuentas y los tokens viven en memoria. Es suficiente para la presentación y evita
 * depender de Supabase o de una base de datos. En un sistema real se usaría un proveedor de
 * identidad y contraseñas con hash.</p>
 */
@Service
public class AuthService {

    private final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private final Map<String, String> passwords = new ConcurrentHashMap<>();
    private final Map<String, Usuario> tokens = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public AuthService() {
        // Cuentas de la jerarquía institucional, una por rol, más la cuenta de estudiante.
        // El profesor dicta solo algunos semestres; el jefe de programa ve todos los de su carrera.
        crearCuenta("Carlos Andrés", "Muñoz Ibarra", "profesor@ucc.edu.co", "Profesor2026",
                "PROFESOR", "Ingeniería de Software", "Bogotá", "1,5,6,8", null, null);
        crearCuenta("Laura Marcela", "Gómez Ruiz", "jefe.programa@ucc.edu.co", "Programa2026",
                "JEFE_PROGRAMA", "Ingeniería de Software", "Medellín", "1,2,3,4,5,6,7,8,9,10", null, null);
        crearCuenta("Diana Carolina", "Mendoza Oviedo", "coordinador.sede@ucc.edu.co", "Coordinador2026",
                "COORDINADOR_SEDE", null, "Bogotá", null, null, null);
        crearCuenta("Óscar Iván", "Restrepo Villa", "decano@ucc.edu.co", "Decano2026",
                "DECANO", null, "Pasto", null, "Facultad de Ingeniería", null);
        crearCuenta("Gloria Patricia", "Salazar Franco", "rector@ucc.edu.co", "Rector2026",
                "RECTOR", null, null, null, null, null);
        crearCuenta("Mariana", "Cárdenas Ortiz", "estudiante@ucc.edu.co", "Estudiante2026",
                "ESTUDIANTE", "Ingeniería de Software", "Pasto", "5", null,
                EstudianteService.ESTUDIANTE_DEMO_ID);

        // Cuentas que ya existían en el demo, ahora dentro de la nueva jerarquía.
        crearCuenta("Andrés Felipe", "Bolaños Cabrera", "facb129@gmail.com", "Permanencia2026", "RECTOR",
                null, null, null, null, null);
        crearCuenta("Esteban", "Ramírez", "esteban@universidad.edu.co", "demo123", "PROFESOR",
                "Ingeniería de Software", "Pasto", "1,5,6,8", null, null);
        crearCuenta("Camila", "Torres", "coordinacion@universidad.edu.co", "demo123", "COORDINADOR_SEDE",
                null, "Pasto", null, null, null);
    }

    public SesionResponse login(String email, String password, String rolSeleccionado, String sedeSeleccionada) {
        String key = normalizar(email);
        Usuario usuario = usuarios.get(key);
        if (usuario == null || !passwords.getOrDefault(key, "").equals(password)) {
            throw ApiException.unauthorized("Correo o contraseña incorrectos");
        }
        // El rol elegido en el login debe corresponder a la cuenta.
        if (rolSeleccionado != null && !rolSeleccionado.isBlank() && !rolSeleccionado.equals(usuario.rol())) {
            throw ApiException.unauthorized("El rol seleccionado no corresponde a esta cuenta");
        }
        // En la demo la sede elegida ajusta el alcance de los roles que trabajan por sede.
        boolean sedeElegida = sedeSeleccionada != null && !sedeSeleccionada.isBlank()
                && ("COORDINADOR_SEDE".equals(usuario.rol()) || "PROFESOR".equals(usuario.rol())
                || "JEFE_PROGRAMA".equals(usuario.rol()));
        if (sedeElegida) {
            usuario = new Usuario(usuario.id(), usuario.nombre(), usuario.apellido(), usuario.email(),
                    usuario.rol(), usuario.programa(), sedeSeleccionada.trim(), usuario.semestres(),
                    usuario.facultad(), usuario.estudianteId());
        }
        return sesion(usuario);
    }

    public SesionResponse registrar(String nombre, String apellido, String email, String password, String rol) {
        String key = normalizar(email);
        if (nombre == null || nombre.isBlank() || apellido == null || apellido.isBlank()) {
            throw ApiException.badRequest("Nombre y apellido son obligatorios");
        }
        if (key.isBlank() || !key.contains("@")) {
            throw ApiException.badRequest("El correo no es válido");
        }
        if (password == null || password.length() < 6 || !password.matches(".*\\d.*")) {
            throw ApiException.badRequest("La contraseña debe tener al menos 6 caracteres y un número");
        }
        if (usuarios.containsKey(key)) {
            throw ApiException.conflict("Ya existe una cuenta con ese correo");
        }
        String rolSeguro = mapearRol(rol);
        Usuario usuario = crearCuenta(nombre.trim(), apellido.trim(), key, password, rolSeguro, null, null,
                null, null, null);
        return sesion(usuario);
    }

    /** Traduce los roles antiguos del registro a la nueva jerarquía de cinco roles. */
    private String mapearRol(String rol) {
        if (rol != null && "COORDINADOR".equalsIgnoreCase(rol)) return "COORDINADOR_SEDE";
        return "PROFESOR";
    }

    public Optional<Usuario> porToken(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(tokens.get(token));
    }

    public Optional<Usuario> porEmail(String email) {
        return Optional.ofNullable(usuarios.get(normalizar(email)));
    }

    private SesionResponse sesion(Usuario usuario) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, usuario);
        return new SesionResponse(usuario.id(), usuario.nombre(), usuario.apellido(), usuario.email(),
                usuario.rol(), usuario.programa(), usuario.sede(), usuario.facultad(),
                usuario.estudianteId(), token);
    }

    private Usuario crearCuenta(String nombre, String apellido, String email, String password, String rol,
            String programa, String sede, String semestres, String facultad, Integer estudianteId) {
        String key = normalizar(email);
        Usuario usuario = new Usuario(nextId.getAndIncrement(), nombre, apellido, key, rol, programa, sede,
                semestres, facultad, estudianteId);
        usuarios.put(key, usuario);
        passwords.put(key, password);
        return usuario;
    }

    private static String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

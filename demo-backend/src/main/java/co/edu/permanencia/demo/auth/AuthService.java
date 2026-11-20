package co.edu.permanencia.demo.auth;

import co.edu.permanencia.demo.common.ApiException;
import co.edu.permanencia.demo.students.EstudianteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Autenticación de la demo, endurecida con las mismas prácticas del backend principal.
 *
 * <p>Las cuentas viven en memoria para que la demo arranque sin base de datos, pero:</p>
 * <ul>
 *   <li>las contraseñas se guardan solo como hash argon2id (mismos parámetros que las cuentas
 *       break-glass del backend principal) y, si el correo no existe, se verifica contra un hash de
 *       relleno para que el tiempo de respuesta no revele qué correos están registrados;</li>
 *   <li>el token de sesión es aleatorio (256 bits) y en memoria solo queda su SHA-256; la sesión
 *       vence por inactividad y por vida máxima, y se puede cerrar ({@link #cerrarSesion});</li>
 *   <li>los intentos fallidos de login se limitan por correo y por IP, y el registro por IP;</li>
 *   <li>el registro público solo crea cuentas de PROFESOR, con sede y programa obligatorios y
 *       validados contra el catálogo: nadie puede darse un rol con más alcance.</li>
 * </ul>
 */
@Service
public class AuthService {

    /** Mismos parámetros que {@code AuthInfrastructureConfig.argon2()} del backend principal. */
    private static final PasswordEncoder HASHER = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    private static final Pattern CORREO = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,}$");
    private static final Duration LOGIN_VENTANA = Duration.ofMinutes(15);
    private static final Duration REGISTRO_VENTANA = Duration.ofHours(1);
    private static final String MENSAJE_BLOQUEO = "Demasiados intentos fallidos. Espera unos minutos e intenta de nuevo.";

    private final Map<String, Usuario> usuarios = new ConcurrentHashMap<>();
    private final Map<String, String> hashes = new ConcurrentHashMap<>();
    /** Clave: SHA-256 del token. El token en claro nunca se guarda. */
    private final Map<String, Sesion> sesiones = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final SecureRandom random = new SecureRandom();
    private final String hashRelleno = HASHER.encode("relleno-para-tiempo-constante");

    private final CatalogoRegistro catalogo;
    private final LimitadorIntentos limitador;
    private final Clock clock;
    private final Duration inactividadMaxima;
    private final Duration vidaMaxima;
    private final Limites limites;

    /**
     * Topes de intentos (configurables por entorno). Las cuentas de la demo son públicas y
     * compartidas: si el bloqueo fuera solo por correo, cinco claves malas de cualquiera dejarían
     * la cuenta bloqueada para todo el salón. Por eso el tope estricto es por correo + IP, con un
     * tope global por correo más alto contra ataques repartidos entre muchas IP.
     *
     * @param fallosCorreoIp fallos de un correo desde una misma IP en 15 min
     * @param fallosCorreo   fallos de un correo desde cualquier IP en 15 min
     * @param fallosIp       fallos desde una IP con cualquier correo en 15 min
     * @param registrosIp    cuentas nuevas por IP por hora (un salón detrás de un mismo NAT comparte IP)
     */
    public record Limites(int fallosCorreoIp, int fallosCorreo, int fallosIp, int registrosIp) {
        public static final Limites POR_DEFECTO = new Limites(5, 50, 30, 30);
    }

    /** Sesión activa. {@code ultimoUso} se actualiza en cada petición autenticada. */
    private static final class Sesion {
        private final Usuario usuario;
        private final Instant creada;
        private volatile Instant ultimoUso;

        private Sesion(Usuario usuario, Instant ahora) {
            this.usuario = usuario;
            this.creada = ahora;
            this.ultimoUso = ahora;
        }
    }

    @Autowired
    public AuthService(CatalogoRegistro catalogo, LimitadorIntentos limitador,
                       @Value("${demo.auth.session-idle-minutes:120}") long inactividadMinutos,
                       @Value("${demo.auth.session-max-hours:12}") long vidaHoras,
                       @Value("${demo.auth.login-max-failures-per-ip-and-email:5}") int fallosCorreoIp,
                       @Value("${demo.auth.login-max-failures-per-email:50}") int fallosCorreo,
                       @Value("${demo.auth.login-max-failures-per-ip:30}") int fallosIp,
                       @Value("${demo.auth.register-max-per-ip-hour:30}") int registrosIp) {
        this(catalogo, limitador, Clock.systemUTC(), Duration.ofMinutes(inactividadMinutos),
                Duration.ofHours(vidaHoras), new Limites(fallosCorreoIp, fallosCorreo, fallosIp, registrosIp));
    }

    AuthService(CatalogoRegistro catalogo, LimitadorIntentos limitador, Clock clock,
                Duration inactividadMaxima, Duration vidaMaxima, Limites limites) {
        this.catalogo = catalogo;
        this.limitador = limitador;
        this.clock = clock;
        this.inactividadMaxima = inactividadMaxima;
        this.vidaMaxima = vidaMaxima;
        this.limites = limites;

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

    /** Login sin información de IP (pruebas y usos internos). */
    public SesionResponse login(String email, String password, String rolSeleccionado, String sedeSeleccionada) {
        return login(email, password, rolSeleccionado, sedeSeleccionada, "local");
    }

    public SesionResponse login(String email, String password, String rolSeleccionado, String sedeSeleccionada,
                                String ip) {
        String key = normalizar(email);
        String claveCorreoIp = "login-correo-ip:" + key + "|" + ip;
        String claveCorreo = "login-correo:" + key;
        String claveIp = "login-ip:" + ip;
        limitador.verificar(claveCorreoIp, limites.fallosCorreoIp(), LOGIN_VENTANA, MENSAJE_BLOQUEO);
        limitador.verificar(claveCorreo, limites.fallosCorreo(), LOGIN_VENTANA, MENSAJE_BLOQUEO);
        limitador.verificar(claveIp, limites.fallosIp(), LOGIN_VENTANA, MENSAJE_BLOQUEO);

        Usuario usuario = usuarios.get(key);
        // Siempre se calcula un hash, exista o no el correo, para no revelar cuáles existen.
        String hash = usuario == null ? hashRelleno : hashes.get(key);
        boolean claveCorrecta = password != null && password.length() <= 128 && HASHER.matches(password, hash);
        if (usuario == null || !claveCorrecta) {
            limitador.registrar(claveCorreoIp, LOGIN_VENTANA);
            limitador.registrar(claveCorreo, LOGIN_VENTANA);
            limitador.registrar(claveIp, LOGIN_VENTANA);
            throw ApiException.unauthorized("Correo o contraseña incorrectos");
        }
        // El rol elegido en el login debe corresponder a la cuenta.
        if (rolSeleccionado != null && !rolSeleccionado.isBlank() && !rolSeleccionado.equals(usuario.rol())) {
            throw ApiException.unauthorized("El rol seleccionado no corresponde a esta cuenta");
        }
        // Cada cuenta está registrada en una sede: no puede entrar desde otra.
        // El Rector es la única excepción porque su alcance es nacional. Este mensaje solo se
        // muestra después de verificar la contraseña, así que no sirve para averiguar cuentas.
        String sedeElegida = sedeSeleccionada == null ? "" : sedeSeleccionada.trim();
        boolean sedeBloqueada = !"RECTOR".equals(usuario.rol())
                && usuario.sede() != null && !usuario.sede().isBlank()
                && !sedeElegida.isBlank()
                && !mismaSede(usuario.sede(), sedeElegida);
        if (sedeBloqueada) {
            throw ApiException.unauthorized(String.format(
                    "Este correo está registrado en la sede %s. No puede iniciar sesión desde %s. "
                            + "Selecciona %s para continuar.",
                    usuario.sede(), sedeElegida, usuario.sede()));
        }
        limitador.limpiar(claveCorreoIp);
        // El alcance se toma de la sede registrada en la cuenta, no de lo que escriba el usuario.
        return sesion(usuario);
    }

    private static boolean mismaSede(String registrada, String elegida) {
        return normalizarSede(registrada).equals(normalizarSede(elegida));
    }

    private static String normalizarSede(String sede) {
        String sinTildes = java.text.Normalizer.normalize(sede.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT);
    }

    /** Registro sin información de IP (pruebas y usos internos). */
    public SesionResponse registrar(String nombre, String apellido, String email, String password, String rol,
                                    String sede, String programa) {
        return registrar(nombre, apellido, email, password, rol, sede, programa, "local");
    }

    /**
     * Registro público. Solo crea profesores con sede y programa del catálogo: su alcance queda
     * limitado a ese programa en esa sede, igual que la cuenta de profesor de la demo.
     */
    public SesionResponse registrar(String nombre, String apellido, String email, String password, String rol,
                                    String sede, String programa, String ip) {
        String claveIp = "registro-ip:" + ip;
        limitador.verificar(claveIp, limites.registrosIp(), REGISTRO_VENTANA,
                "Se crearon demasiadas cuentas desde esta conexión. Intenta más tarde.");

        String key = normalizar(email);
        if (vacio(nombre) || vacio(apellido) || nombre.length() > 80 || apellido.length() > 80) {
            throw ApiException.badRequest("Nombre y apellido son obligatorios (máximo 80 caracteres)");
        }
        if (key.length() > 254 || !CORREO.matcher(key).matches()) {
            throw ApiException.badRequest("El correo no es válido");
        }
        if (password == null || password.length() < 8 || password.length() > 128
                || !password.matches(".*\\d.*") || !password.matches(".*\\p{L}.*")) {
            throw ApiException.badRequest("La contraseña debe tener al menos 8 caracteres, con letras y números");
        }
        if (rol != null && !rol.isBlank() && !"PROFESOR".equalsIgnoreCase(rol)) {
            throw ApiException.badRequest("El registro público solo crea cuentas de profesor");
        }
        String sedeValida = sedeDelCatalogo(sede);
        String programaValido = programaDelCatalogo(sedeValida, programa);

        limitador.registrar(claveIp, REGISTRO_VENTANA);
        if (usuarios.containsKey(key)) {
            throw ApiException.conflict("Ya existe una cuenta con ese correo");
        }
        Usuario usuario = crearCuenta(nombre.trim(), apellido.trim(), key, password, "PROFESOR",
                programaValido, sedeValida, null, null, null);
        return sesion(usuario);
    }

    /** Sedes y programas que el formulario de registro puede ofrecer. */
    public Map<String, List<String>> opcionesRegistro() {
        return catalogo.programasPorSede();
    }

    private String sedeDelCatalogo(String sede) {
        if (vacio(sede)) {
            throw ApiException.badRequest("Selecciona la sede donde dictas clase");
        }
        return catalogo.programasPorSede().keySet().stream()
                .filter(s -> mismaSede(s, sede))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("La sede no es válida"));
    }

    private String programaDelCatalogo(String sede, String programa) {
        if (vacio(programa)) {
            throw ApiException.badRequest("Selecciona el programa en el que dictas clase");
        }
        return catalogo.programasPorSede().getOrDefault(sede, List.of()).stream()
                .filter(p -> p.equalsIgnoreCase(programa.trim()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("Ese programa no se dicta en la sede elegida"));
    }

    /**
     * Usuario dueño del token, si la sesión sigue viva. Renueva el último uso (vencimiento por
     * inactividad) y descarta la sesión si venció.
     */
    public Optional<Usuario> porToken(String token) {
        if (token == null || token.isBlank() || token.length() > 200) {
            return Optional.empty();
        }
        String clave = huella(token);
        Sesion sesion = sesiones.get(clave);
        if (sesion == null) {
            return Optional.empty();
        }
        Instant ahora = clock.instant();
        if (vencida(sesion, ahora)) {
            sesiones.remove(clave);
            return Optional.empty();
        }
        sesion.ultimoUso = ahora;
        return Optional.of(sesion.usuario);
    }

    /** Cierra la sesión del token (idempotente). */
    public void cerrarSesion(String token) {
        if (token != null && !token.isBlank() && token.length() <= 200) {
            sesiones.remove(huella(token));
        }
    }

    public Optional<Usuario> porEmail(String email) {
        return Optional.ofNullable(usuarios.get(normalizar(email)));
    }

    private boolean vencida(Sesion sesion, Instant ahora) {
        return ahora.isAfter(sesion.ultimoUso.plus(inactividadMaxima)) || ahora.isAfter(sesion.creada.plus(vidaMaxima));
    }

    private SesionResponse sesion(Usuario usuario) {
        Instant ahora = clock.instant();
        sesiones.values().removeIf(s -> vencida(s, ahora));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sesiones.put(huella(token), new Sesion(usuario, ahora));
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
        hashes.put(key, HASHER.encode(password));
        return usuario;
    }

    private static String huella(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }

    private static String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

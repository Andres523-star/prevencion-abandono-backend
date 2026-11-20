package co.edu.permanencia.demo.auth;

import co.edu.permanencia.demo.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Login, registro y cierre de sesión de la demostración.
 *
 * <p>La IP que se usa para limitar intentos es {@code request.getRemoteAddr()}: detrás del proxy
 * de Render, Tomcat la toma de {@code X-Forwarded-For} solo si el proxy es de confianza
 * ({@code server.forward-headers-strategy: native}), así que un cliente no puede falsearla.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public SesionResponse login(@RequestBody LoginRequest request, HttpServletRequest http) {
        return auth.login(request.email(), request.password(), request.rol(), request.sede(), http.getRemoteAddr());
    }

    @PostMapping("/register")
    public SesionResponse registrar(@RequestBody RegisterRequest request, HttpServletRequest http) {
        return auth.registrar(request.nombre(), request.apellido(), request.email(), request.password(),
                request.rol(), request.sede(), request.programa(), http.getRemoteAddr());
    }

    /** Sedes y programas para el formulario de registro (público: se usa antes de tener cuenta). */
    @GetMapping("/opciones-registro")
    public Map<String, List<String>> opcionesRegistro() {
        return auth.opcionesRegistro();
    }

    /** Cierra la sesión en el servidor: el token deja de servir de inmediato. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(name = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            auth.cerrarSesion(authorization.substring(7));
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(name = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw ApiException.unauthorized("Sesión inválida");
        }
        Usuario usuario = auth.porToken(authorization.substring(7))
                .orElseThrow(() -> ApiException.unauthorized("Sesión inválida"));
        return Map.of("id", usuario.id(), "nombre", usuario.nombre(), "apellido", usuario.apellido(),
                "email", usuario.email(), "rol", usuario.rol(), "programa", usuario.programa() == null ? "" : usuario.programa(),
                "sede", usuario.sede() == null ? "" : usuario.sede(),
                "estudianteId", usuario.estudianteId() == null ? 0 : usuario.estudianteId());
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password, String rol, String sede) {
    }

    public record RegisterRequest(String nombre, String apellido, String email, String password, String rol,
                                  String sede, String programa) {
    }
}

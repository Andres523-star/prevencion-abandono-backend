package co.edu.permanencia.demo.auth;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Login y registro simplificados para la demostración. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public SesionResponse login(@RequestBody LoginRequest request) {
        return auth.login(request.email(), request.password(), request.rol(), request.sede());
    }

    @PostMapping("/register")
    public SesionResponse registrar(@RequestBody RegisterRequest request) {
        return auth.registrar(request.nombre(), request.apellido(), request.email(), request.password(), request.rol());
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(name = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw co.edu.permanencia.demo.common.ApiException.unauthorized("Sesión inválida");
        }
        Usuario usuario = auth.porToken(authorization.substring(7))
                .orElseThrow(() -> co.edu.permanencia.demo.common.ApiException.unauthorized("Sesión inválida"));
        return Map.of("id", usuario.id(), "nombre", usuario.nombre(), "apellido", usuario.apellido(),
                "email", usuario.email(), "rol", usuario.rol(), "programa", usuario.programa() == null ? "" : usuario.programa(),
                "sede", usuario.sede() == null ? "" : usuario.sede(),
                "estudianteId", usuario.estudianteId() == null ? 0 : usuario.estudianteId());
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password, String rol, String sede) {
    }

    public record RegisterRequest(String nombre, String apellido, String email, String password, String rol) {
    }
}

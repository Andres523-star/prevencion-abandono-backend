package co.edu.permanencia.demo.config;

import co.edu.permanencia.demo.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;

/** Exige un token Bearer en las rutas privadas de la API. */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService auth;
    private final ObjectMapper mapper;

    public AuthInterceptor(AuthService auth, ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || path.startsWith("/api/auth/")
                || path.equals("/api/salud")
                || path.startsWith("/api/chats/adjuntos/")) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return denegar(response, "Debes iniciar sesión");
        }
        var usuario = auth.porToken(header.substring(7));
        if (usuario.isEmpty()) {
            return denegar(response, "La sesión expiró, vuelve a entrar");
        }
        request.setAttribute("usuario", usuario.get());
        return true;
    }

    private boolean denegar(HttpServletResponse response, String mensaje) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), Map.of("error", mensaje));
        return false;
    }
}

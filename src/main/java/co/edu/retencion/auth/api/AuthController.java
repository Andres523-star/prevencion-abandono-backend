package co.edu.retencion.auth.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.auth.application.BreakGlassLoginService;
import co.edu.retencion.auth.application.SupabaseLoginService;
import co.edu.retencion.auth.application.SessionService;
import co.edu.retencion.auth.application.SessionTokens;
import co.edu.retencion.common.security.CurrentUser;

/**
 * Endpoints de autenticación. Todos son públicos salvo logout; tienen rate limit estricto por IP.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticación")
public class AuthController {

    private final SupabaseLoginService supabaseLogin;
    private final BreakGlassLoginService breakGlassLogin;
    private final SessionService sessions;

    public AuthController(SupabaseLoginService supabaseLogin, BreakGlassLoginService breakGlassLogin,
                          SessionService sessions) {
        this.supabaseLogin = supabaseLogin;
        this.breakGlassLogin = breakGlassLogin;
        this.sessions = sessions;
    }

    /** @param accessToken access_token que supabase-js entregó al frontend tras iniciar sesión */
    public record ExchangeRequest(@NotBlank @Size(max = 8192) String accessToken) {

        @Override
        public String toString() {
            return "ExchangeRequest[***]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[***]";
        }
    }

    public record BreakGlassLoginRequest(@NotBlank @Size(max = 64) String username,
                                         @NotBlank @Size(max = 256) String password,
                                         @NotBlank @Pattern(regexp = "\\d{6}") String totpCode) {

        @Override
        public String toString() {
            return "BreakGlassLoginRequest[***]";
        }
    }

    @PostMapping("/supabase/exchange")
    @SecurityRequirements
    @Audited(action = "AUTH_SUPABASE_LOGIN", entity = "User", entityId = "#result.userId")
    @Operation(summary = "Canjea el access token de Supabase Auth por la sesión de la plataforma")
    public SessionTokens exchange(@Valid @RequestBody ExchangeRequest request) {
        return supabaseLogin.exchange(request.accessToken());
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Audited(action = "AUTH_REFRESH", entity = "User", entityId = "#result.userId")
    @Operation(summary = "Renueva la sesión (rota el refresh token; reutilizarlo revoca la sesión)")
    public SessionTokens refresh(@Valid @RequestBody RefreshRequest request) {
        return sessions.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Audited(action = "AUTH_LOGOUT", entity = "Session")
    @Operation(summary = "Cierra la sesión actual")
    public void logout() {
        sessions.logout(CurrentUser.require().sessionId());
    }

    @PostMapping("/break-glass/login")
    @SecurityRequirements
    @Audited(action = "AUTH_BREAK_GLASS_LOGIN", entity = "User", entityId = "#result.userId")
    @Operation(summary = "Login de emergencia (solo ADMIN): contraseña + TOTP")
    public SessionTokens breakGlass(@Valid @RequestBody BreakGlassLoginRequest request) {
        return breakGlassLogin.login(request.username(), request.password(), request.totpCode());
    }
}

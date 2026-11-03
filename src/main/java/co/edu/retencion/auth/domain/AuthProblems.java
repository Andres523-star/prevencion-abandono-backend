package co.edu.retencion.auth.domain;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import co.edu.retencion.common.error.ApiProblem;
import co.edu.retencion.common.error.ProblemCatalogContributor;

/** Prototipos de error de autenticación (patrón Prototype, ver {@code ProblemCatalog}). */
@Component
public class AuthProblems implements ProblemCatalogContributor {

    public static final String MFA_REQUIRED = "mfa_required";
    public static final String INVALID_CREDENTIALS = "invalid_credentials";
    public static final String INVALID_REFRESH_TOKEN = "invalid_refresh_token";
    public static final String REFRESH_TOKEN_REUSED = "refresh_token_reused";
    public static final String ACCOUNT_DISABLED = "account_disabled";
    public static final String EMAIL_DOMAIN_NOT_ALLOWED = "email_domain_not_allowed";
    public static final String EMAIL_NOT_VERIFIED = "email_not_verified";
    public static final String INVALID_PROVIDER_TOKEN = "invalid_provider_token";
    public static final String PROVIDER_TOKEN_ALREADY_USED = "provider_token_already_used";
    public static final String AUTH_PROVIDER_ERROR = "auth_provider_error";
    public static final String AUTH_PROVIDER_UNAVAILABLE = "auth_provider_unavailable";
    public static final String AUTH_NOT_CONFIGURED = "auth_not_configured";

    @Override
    public List<ApiProblem> problems() {
        return List.of(
                new ApiProblem(MFA_REQUIRED, HttpStatus.FORBIDDEN, "Tu rol requiere autenticación multifactor (MFA)"),
                new ApiProblem(INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Credenciales inválidas"),
                new ApiProblem(INVALID_REFRESH_TOKEN, HttpStatus.UNAUTHORIZED, "La sesión expiró o no es válida"),
                new ApiProblem(REFRESH_TOKEN_REUSED, HttpStatus.UNAUTHORIZED,
                        "Se detectó reutilización del token de sesión; todas las sesiones relacionadas fueron cerradas"),
                new ApiProblem(ACCOUNT_DISABLED, HttpStatus.FORBIDDEN, "La cuenta está desactivada"),
                new ApiProblem(EMAIL_DOMAIN_NOT_ALLOWED, HttpStatus.FORBIDDEN, "El dominio del correo no está permitido"),
                new ApiProblem(EMAIL_NOT_VERIFIED, HttpStatus.FORBIDDEN, "Debes confirmar tu correo antes de ingresar"),
                new ApiProblem(INVALID_PROVIDER_TOKEN, HttpStatus.UNAUTHORIZED, "El token de Supabase no es válido o expiró"),
                new ApiProblem(PROVIDER_TOKEN_ALREADY_USED, HttpStatus.UNAUTHORIZED,
                        "Ese token de Supabase ya se canjeó; inicia sesión de nuevo"),
                new ApiProblem(AUTH_PROVIDER_ERROR, HttpStatus.BAD_GATEWAY, "Error al comunicarse con Supabase Auth"),
                new ApiProblem(AUTH_PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Supabase Auth no está disponible temporalmente"),
                new ApiProblem(AUTH_NOT_CONFIGURED, HttpStatus.SERVICE_UNAVAILABLE, "El inicio de sesión no está configurado"));
    }
}

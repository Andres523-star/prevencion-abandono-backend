package co.edu.retencion.common.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documento OpenAPI con esquema de seguridad Bearer (JWT de la plataforma). Swagger UI solo se
 * habilita en dev; en prod el documento queda desactivado salvo que se active por entorno.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI retencionOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("API de retención estudiantil")
                        .version("v1")
                        .description("Detección, predicción y prevención de la deserción estudiantil. "
                                + "Autenticación: Supabase Auth (cualquier correo verificado) → JWT propio de corta duración."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Token de acceso emitido por /api/v1/auth/* (vida máxima 15 min)")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}

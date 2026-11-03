package co.edu.retencion.auth.infrastructure;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.retencion.common.security.PublicEndpoints;
import co.edu.retencion.common.state.DistributedStateFactory;
import co.edu.retencion.common.state.OneTimeTicketStore;

/** Beans de infraestructura del módulo auth. */
@Configuration(proxyBeanMethods = false)
public class AuthInfrastructureConfig {

    /** Rutas de login/renovación que no llevan JWT. */
    @Bean
    PublicEndpoints authPublicEndpoints() {
        return new PublicEndpoints(List.of(
                "/api/v1/auth/supabase/exchange",
                "/api/v1/auth/refresh",
                "/api/v1/auth/break-glass/login"));
    }

    /**
     * argon2id con los parámetros mínimos recomendados por OWASP (m=19 MiB, t=2, p=1).
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return argon2();
    }

    /** Anti-replay de tokens de Supabase ya canjeados, compartido entre instancias si el backend es Redis. */
    @Bean
    OneTimeTicketStore exchangedTokenStore(DistributedStateFactory stateFactory) {
        return stateFactory.createTicketStore("supabase-exchanged");
    }

    static Argon2PasswordEncoder argon2() {
        return new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
    }
}

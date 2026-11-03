package co.edu.retencion.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import co.edu.retencion.support.IntegrationTest;

/**
 * Rate limiting estricto en rutas de autenticación: al superar el límite responde 429 con
 * Retry-After. Usa un contexto propio con un límite bajo.
 */
@TestPropertySource(properties = "app.rate-limit.sensitive.capacity=3")
class RateLimitIT extends IntegrationTest {

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    void sensitiveEndpointsAreThrottledPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/refresh").with(fromIp("10.20.30.40"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/refresh").with(fromIp("10.20.30.40"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("rate_limited"));

        // Otra IP tiene su propio cubo.
        mvc.perform(post("/api/v1/auth/refresh").with(fromIp("10.20.30.41"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}

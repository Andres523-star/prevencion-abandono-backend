package co.edu.retencion.common.async;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Ejecutor de los listeners asíncronos de eventos de dominio ({@code @ApplicationModuleListener}).
 *
 * <p>Por qué existe: con hilos virtuales, Spring usa un ejecutor sin límite. En un pico de asistencia (todos
 * los docentes tomando lista a la vez) cada falta dispara varios eventos y cientos de listeners piden una
 * conexión al mismo tiempo; el pool se agota y las peticiones de los docentes fallan por timeout. Con un
 * número fijo de hilos y una cola, los listeners esperan su turno, las peticiones conservan sus conexiones y
 * el trabajo diferido se completa unos segundos después. Prueba: {@code AttendancePeakIT}.
 *
 * <p>Regla de dimensionamiento: {@code listener-threads} + peticiones concurrentes ≤ {@code DB_POOL_MAX}
 * con margen (ver docs/dimensionamiento.md).
 */
@Configuration(proxyBeanMethods = false)
public class AsyncExecutorConfig {

    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    ThreadPoolTaskExecutor applicationTaskExecutor(@Value("${app.async.listener-threads:6}") int threads,
                                                   @Value("${app.async.queue-capacity:200000}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("eventos-");
        // Al apagar, termina lo que está en curso; lo pendiente lo reintenta el registro de publicaciones
        // al arrancar (republish-outstanding-events-on-restart).
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}

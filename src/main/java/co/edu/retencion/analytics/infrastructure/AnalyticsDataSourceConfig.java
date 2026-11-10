package co.edu.retencion.analytics.infrastructure;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Conexión de lectura de la analítica. Con {@code APP_ANALYTICS_READ_URL} las consultas de los gráficos,
 * el tablero y los reportes van a una réplica de lectura y no compiten con la toma de asistencia; sin ella
 * usan la base principal. El refresco de las vistas siempre corre en la principal (las vistas
 * materializadas llegan a la réplica por la replicación física).
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsDataSourceConfig {

    @Bean
    @Qualifier("analyticsRead")
    NamedParameterJdbcTemplate analyticsReadJdbc(DataSource primary,
                                                 @Value("${app.analytics.read.url:}") String url,
                                                 @Value("${app.analytics.read.username:}") String username,
                                                 @Value("${app.analytics.read.password:}") String password,
                                                 @Value("${app.analytics.read.pool-size:5}") int poolSize) {
        if (url == null || url.isBlank()) {
            return new NamedParameterJdbcTemplate(primary);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setReadOnly(true);
        config.setPoolName("analytics-read");
        return new NamedParameterJdbcTemplate(new HikariDataSource(config));
    }
}

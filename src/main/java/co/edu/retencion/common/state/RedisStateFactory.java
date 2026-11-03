package co.edu.retencion.common.state;

import java.time.Duration;
import java.util.Optional;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import co.edu.retencion.common.ratelimit.Bucket4jRedisRateLimiter;
import co.edu.retencion.common.ratelimit.RateLimiter;

/**
 * Fábrica concreta de la familia "Redis" (Render Key Value, Redis, Valkey). Todos los
 * componentes comparten el mismo cliente Lettuce.
 */
public class RedisStateFactory implements DistributedStateFactory {

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    public RedisStateFactory(RedisClient client) {
        this.client = client;
        this.connection = client.connect();
    }

    @Override
    public String backend() {
        return "redis";
    }

    @Override
    public RateLimiter createRateLimiter() {
        return new Bucket4jRedisRateLimiter(client);
    }

    @Override
    public OneTimeTicketStore createTicketStore(String namespace) {
        return new RedisTicketStore(connection.sync(), "ticket:" + namespace + ":");
    }

    /** Comando PING para el health check. */
    public String ping() {
        return connection.sync().ping();
    }

    public void close() {
        connection.close();
        client.shutdown();
    }

    /** <b>Adapter</b> de comandos Redis (SET PX / GETDEL / SET NX) a {@link OneTimeTicketStore}. */
    static final class RedisTicketStore implements OneTimeTicketStore {

        private final RedisCommands<String, String> commands;
        private final String prefix;

        RedisTicketStore(RedisCommands<String, String> commands, String prefix) {
            this.commands = commands;
            this.prefix = prefix;
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            commands.set(prefix + key, value, SetArgs.Builder.px(ttl.toMillis()));
        }

        @Override
        public Optional<String> take(String key) {
            // GETDEL es atómico (Redis >= 6.2): evita que dos peticiones usen el mismo ticket.
            return Optional.ofNullable(commands.getdel(prefix + key));
        }

        @Override
        public boolean claim(String key, Duration ttl) {
            // SET NX PX: solo la primera instancia que lo intente obtiene "OK".
            return "OK".equals(commands.set(prefix + key, "1", SetArgs.Builder.nx().px(ttl.toMillis())));
        }
    }
}

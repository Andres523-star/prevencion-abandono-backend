package co.edu.retencion.common.state;

import java.time.Duration;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import co.edu.retencion.common.ratelimit.Bucket4jLocalRateLimiter;
import co.edu.retencion.common.ratelimit.RateLimiter;

/**
 * Fábrica concreta de la familia "en memoria". Válida solo con UNA instancia del backend.
 */
public class InMemoryStateFactory implements DistributedStateFactory {

    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public RateLimiter createRateLimiter() {
        return new Bucket4jLocalRateLimiter();
    }

    @Override
    public OneTimeTicketStore createTicketStore(String namespace) {
        return new InMemoryTicketStore();
    }

    /** Tickets en Caffeine con vencimiento individual por entrada. */
    static final class InMemoryTicketStore implements OneTimeTicketStore {

        private record Ticket(String value, Duration ttl) {
        }

        private final Cache<String, Ticket> cache = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfter(new Expiry<String, Ticket>() {
                    @Override
                    public long expireAfterCreate(String key, Ticket ticket, long currentTime) {
                        return ticket.ttl().toNanos();
                    }

                    @Override
                    public long expireAfterUpdate(String key, Ticket ticket, long currentTime, long currentDuration) {
                        return ticket.ttl().toNanos();
                    }

                    @Override
                    public long expireAfterRead(String key, Ticket ticket, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();

        @Override
        public void put(String key, String value, Duration ttl) {
            cache.put(key, new Ticket(value, ttl));
        }

        @Override
        public Optional<String> take(String key) {
            Ticket ticket = cache.asMap().remove(key);
            return Optional.ofNullable(ticket).map(Ticket::value);
        }

        @Override
        public boolean claim(String key, Duration ttl) {
            return cache.asMap().putIfAbsent(key, new Ticket("1", ttl)) == null;
        }
    }
}

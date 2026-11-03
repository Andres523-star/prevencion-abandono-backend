package co.edu.retencion.common.state;

import co.edu.retencion.common.ratelimit.RateLimiter;

/**
 * <b>Patrón creacional: Abstract Factory.</b> Crea la <em>familia</em> de componentes de estado
 * compartido (rate limiter y almacén de tickets de un solo uso) de forma coherente: o todos en memoria
 * ({@link InMemoryStateFactory}, para dev/test con una sola instancia) o todos sobre Redis
 * ({@link RedisStateFactory}, para producción con varias instancias). Así nunca se mezcla un
 * rate limiter en Redis con un anti-replay en memoria que otra instancia no ve.
 */
public interface DistributedStateFactory {

    /** Nombre del backend ({@code memory} o {@code redis}). */
    String backend();

    RateLimiter createRateLimiter();

    /** Almacén de tickets aislado por espacio de nombres (prefijo de clave). */
    OneTimeTicketStore createTicketStore(String namespace);
}

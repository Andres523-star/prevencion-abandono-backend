package co.edu.retencion.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reloj de pruebas: avanza con el tiempo real más un desfase ajustable, para probar
 * vencimientos y bloqueos sin esperar.
 */
public class MutableClock extends Clock {

    private final AtomicReference<Duration> offset;
    private final ZoneId zone;

    public MutableClock() {
        this(new AtomicReference<>(Duration.ZERO), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Duration> offset, ZoneId zone) {
        this.offset = offset;
        this.zone = zone;
    }

    public void advance(Duration duration) {
        offset.updateAndGet(current -> current.plus(duration));
    }

    /** Fija el instante actual (el reloj sigue corriendo desde ahí). */
    public void setNow(Instant now) {
        offset.set(Duration.between(Instant.now(), now));
    }

    public void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** Vista en otra zona que comparte el mismo desfase (p. ej. fecha de negocio en Bogotá). */
    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(offset, zone);
    }
}

package co.edu.retencion.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generador de identificadores UUID versión 7 (RFC 9562): ordenables en el tiempo, lo que
 * mantiene compactos los índices B-tree en tablas grandes (asistencia, auditoría).
 *
 * <p><b>Patrón creacional: Singleton.</b> Se implementa como {@code enum} de un solo valor
 * (forma recomendada en Java: segura ante serialización y reflexión). Debe existir una única
 * instancia porque guarda estado compartido (último milisegundo y contador) para garantizar
 * que los IDs generados en el mismo proceso sean estrictamente crecientes.
 *
 * <p>Distribución de bits: 48 de timestamp Unix en ms | 4 de versión | 12 de contador
 * monotónico | 2 de variante | 62 aleatorios.
 */
public enum UuidV7 {

    INSTANCE;

    private static final int COUNTER_MAX = 0xFFF;

    private final SecureRandom random = new SecureRandom();
    private long lastMillis = -1L;
    private int counter;

    /** Atajo para el uso más común: {@code UuidV7.newId()}. */
    public static UUID newId() {
        return INSTANCE.next();
    }

    /** Genera el siguiente UUID v7, monotónico dentro del proceso. */
    public synchronized UUID next() {
        long now = System.currentTimeMillis();
        if (now > lastMillis) {
            lastMillis = now;
            // Se arranca en la mitad inferior para dejar margen de incremento en el mismo ms.
            counter = random.nextInt(COUNTER_MAX / 2);
        } else if (++counter > COUNTER_MAX) {
            // Contador agotado o reloj hacia atrás: avanzamos el ms lógico para no repetir orden.
            lastMillis++;
            counter = 0;
        }
        long msb = ((lastMillis & 0xFFFF_FFFF_FFFFL) << 16) | (0x7L << 12) | counter;
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}

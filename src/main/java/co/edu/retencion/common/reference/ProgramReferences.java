package co.edu.retencion.common.reference;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * SPI para validar referencias a programas académicos desde módulos que no pueden depender de
 * {@code academic} (p. ej. {@code users}, que academic a su vez usa: evita el ciclo).
 * La implementa el módulo academic.
 */
public interface ProgramReferences {

    /** Devuelve los ids de la colección que NO existen. */
    Set<UUID> missing(Collection<UUID> programIds);
}

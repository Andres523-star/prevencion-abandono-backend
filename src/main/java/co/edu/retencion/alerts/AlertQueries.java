package co.edu.retencion.alerts;

import java.util.Set;
import java.util.UUID;

/** Conteos de alertas para el tablero. */
public interface AlertQueries {

    /** Alertas abiertas (NUEVA o VISTA) en esos programas; {@code null} = todos. */
    long openCount(Set<UUID> programIds);
}

package co.edu.retencion.records;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import co.edu.retencion.common.data.UpsertOutcome;

/** API pública de notas para la importación desde el sistema académico. */
public interface GradeBook {

    /** Clave natural: inscripción + fecha + descripción. */
    UpsertOutcome upsert(UUID enrollmentId, BigDecimal value, LocalDate assessedOn, String description, UUID actor);
}

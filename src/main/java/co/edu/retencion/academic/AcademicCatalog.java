package co.edu.retencion.academic;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import co.edu.retencion.common.data.UpsertOutcome;

/**
 * Altas idempotentes por clave natural para la importación masiva y los datos sintéticos.
 * Aplican las mismas validaciones de negocio que la API REST.
 */
public interface AcademicCatalog {

    /** Crea el periodo si no existe (clave: código). */
    TermInfo ensureTerm(String code, String name, LocalDate startDate, LocalDate endDate, String status);

    /** Clave natural: programa + código de materia. */
    Upsert upsertSubject(SubjectCommand command);

    /** Clave natural: materia + periodo + código de grupo. Reemplaza el horario si viene. */
    Upsert upsertGroup(GroupCommand command);

    /** Genera (idempotente) las sesiones de todos los grupos del periodo; devuelve las creadas. */
    int generateSessionsForTerm(UUID termId);

    /** Carga (idempotente) los festivos de Colombia del año. */
    void loadColombianHolidays(int year);

    /**
     * @param id      id del registro
     * @param outcome creado, actualizado o sin cambios
     */
    record Upsert(UUID id, UpsertOutcome outcome) {
    }

    /**
     * @param programId         programa
     * @param code              código de la materia
     * @param name              nombre
     * @param credits           créditos
     * @param suggestedSemester semestre sugerido
     * @param prerequisiteCodes códigos de materias prerrequisito del mismo programa
     */
    record SubjectCommand(UUID programId, String code, String name, int credits, int suggestedSemester,
                          List<String> prerequisiteCodes) {
    }

    /**
     * @param subjectId     materia
     * @param termId        periodo
     * @param code          código del grupo
     * @param teacherUserId docente (usuario con rol DOCENTE)
     * @param capacity      cupo
     * @param groupType     tipo de grupo
     * @param schedule      franjas semanales; vacío = no se toca el horario
     */
    record GroupCommand(UUID subjectId, UUID termId, String code, UUID teacherUserId, int capacity,
                        GroupType groupType, List<ScheduleSlot> schedule) {
    }

    /**
     * Franja semanal de clase.
     *
     * @param day   día de la semana
     * @param start hora de inicio
     * @param end   hora de fin
     * @param room  aula
     */
    record ScheduleSlot(DayOfWeek day, LocalTime start, LocalTime end, String room) {
    }
}

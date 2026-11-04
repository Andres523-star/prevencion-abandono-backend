package co.edu.retencion.risk.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import co.edu.retencion.records.AttendanceLevel;

/**
 * Planes de apoyo por reglas (cuando la IA no se pide o falla).
 *
 * <p><b>Patrón Prototype:</b> hay un plan prototipo por nivel de asistencia (AVISO, CRÍTICO,
 * PÉRDIDA). Cada plan nuevo es una copia del prototipo ({@link PlanTemplate#copy()}) a la que se
 * agregan acciones según el contexto; el prototipo nunca se modifica.
 *
 * <p>Las acciones nunca se derivan de datos de bienestar o salud (el plan lo ven roles que no
 * pueden conocerlos): la orientación de bienestar se ofrece de forma genérica en los niveles altos.
 */
public final class SupportPlanTemplates {

    private static final Map<AttendanceLevel, PlanTemplate> PROTOTYPES = new EnumMap<>(AttendanceLevel.class);

    static {
        PROTOTYPES.put(AttendanceLevel.AVISO, new PlanTemplate(List.of(
                new PlanAction("CONTACTO_DOCENTE", "Conversación con el docente",
                        "Hablar con el estudiante sobre sus faltas en {{materia}}, recordarle el plazo para justificar y acordar cómo ponerse al día.",
                        "MEDIA", "DOCENTE", null, 3)),
                "Hola {{nombre}}, notamos algunas ausencias en {{materia}}. Queremos ayudarte a ponerte al día: si tuviste "
                        + "alguna dificultad, cuéntanos y revisemos juntos las opciones de apoyo de la universidad."));
        PROTOTYPES.put(AttendanceLevel.CRITICO, new PlanTemplate(List.of(
                new PlanAction("CONTACTO_DOCENTE", "Contacto prioritario del docente",
                        "Contactar al estudiante esta semana para entender las ausencias en {{materia}} y acordar un plan para no perder la materia.",
                        "ALTA", "DOCENTE", null, 2),
                new PlanAction("CONSEJERIA", "Cita de consejería académica",
                        "Revisar con el estudiante su situación en {{materia}} y las barreras que le impiden asistir.",
                        "ALTA", "CONSEJERO", "CONSEJERIA-ACADEMICA", 5),
                new PlanAction("REMISION_BIENESTAR", "Ofrecer orientación de bienestar",
                        "Informar al estudiante sobre los servicios de bienestar por si los necesita (sin obligación).",
                        "BAJA", "CONSEJERO", "BIENESTAR-ACOMPANAMIENTO", 7)),
                "Hola {{nombre}}, estamos atentos a tu proceso en {{materia}}. Ya tienes varias ausencias y queremos "
                        + "acompañarte para que puedas terminar la materia. Tu consejero te contactará para revisar juntos cómo apoyarte."));
        PROTOTYPES.put(AttendanceLevel.PERDIDA, new PlanTemplate(List.of(
                new PlanAction("CONSEJERIA", "Consejería tras pérdida por inasistencia",
                        "Revisar con el estudiante las opciones académicas después de la pérdida de {{materia}} y su plan para el siguiente periodo.",
                        "ALTA", "CONSEJERO", "CONSEJERIA-ACADEMICA", 2),
                new PlanAction("AJUSTE_HORARIO", "Revisión de carga académica",
                        "Evaluar con la coordinación si conviene ajustar la carga o el horario para proteger las demás materias.",
                        "MEDIA", "COORDINADOR", "FLEX-ACADEMICA", 10),
                new PlanAction("REMISION_BIENESTAR", "Ofrecer orientación de bienestar",
                        "Informar al estudiante sobre los servicios de bienestar por si los necesita (sin obligación).",
                        "BAJA", "CONSEJERO", "BIENESTAR-ACOMPANAMIENTO", 7)),
                "Hola {{nombre}}, sabemos que {{materia}} no salió como esperabas. Queremos ayudarte a cuidar el resto de "
                        + "tu semestre: tu consejero te contactará para revisar juntos las opciones disponibles."));
    }

    private SupportPlanTemplates() {
    }

    /**
     * Acción de un plan.
     *
     * @param type           tipo de acción
     * @param title          título
     * @param description    descripción (puede usar {{materia}})
     * @param priority       ALTA, MEDIA o BAJA
     * @param suggestedOwner rol sugerido
     * @param resourceId     recurso del catálogo o {@code null}
     * @param dueInDays      plazo sugerido
     */
    public record PlanAction(String type, String title, String description, String priority, String suggestedOwner,
                             String resourceId, int dueInDays) {

        PlanAction withResource(String id) {
            return new PlanAction(type, title, description, priority, suggestedOwner, id, dueInDays);
        }
    }

    /** Plan mutable construido a partir de un prototipo. */
    public static final class PlanTemplate {

        private final List<PlanAction> actions;
        private final String studentMessage;

        PlanTemplate(List<PlanAction> actions, String studentMessage) {
            this.actions = new ArrayList<>(actions);
            this.studentMessage = studentMessage;
        }

        /** Copia independiente (el prototipo no cambia). */
        public PlanTemplate copy() {
            return new PlanTemplate(actions, studentMessage);
        }

        public PlanTemplate add(PlanAction action) {
            if (actions.stream().noneMatch(a -> a.type().equals(action.type()))) {
                actions.add(action);
            }
            return this;
        }

        public List<PlanAction> actions() {
            return List.copyOf(actions);
        }

        public String studentMessage() {
            return studentMessage;
        }
    }

    /**
     * Contexto para ajustar el plan.
     *
     * @param belowPassing      la materia va por debajo de 3.0
     * @param works             el estudiante trabaja
     * @param publicTransport   usa transporte público
     * @param eligibleResources recursos para los que es elegible (ya filtrados en el backend)
     */
    public record Context(boolean belowPassing, boolean works, boolean publicTransport, Set<String> eligibleResources) {
    }

    /** Copia del prototipo del nivel con acciones según el contexto. */
    public static PlanTemplate forLevel(AttendanceLevel level, Context ctx) {
        PlanTemplate prototype = PROTOTYPES.get(level == AttendanceLevel.NINGUNO ? AttendanceLevel.AVISO : level);
        PlanTemplate plan = prototype.copy();
        if (ctx.belowPassing()) {
            plan.add(new PlanAction("TUTORIA", "Tutoría o monitoría",
                    "Inscribir al estudiante en tutoría o monitoría de {{materia}} para recuperar los temas perdidos.",
                    "ALTA", "DOCENTE", "TUT-ACADEMICA", 7));
        }
        if (ctx.works()) {
            plan.add(new PlanAction("AJUSTE_HORARIO", "Ajuste de horario",
                    "Explorar un cambio de grupo u horario compatible con su trabajo.", "MEDIA", "COORDINADOR",
                    "FLEX-ACADEMICA", 10));
        }
        if (ctx.publicTransport()) {
            plan.add(new PlanAction("APOYO_TRANSPORTE", "Apoyo de transporte",
                    "Revisar si puede acceder al auxilio de transporte.", "MEDIA", "CONSEJERO", "AUX-TRANSPORTE", 7));
        }
        // Solo se citan recursos para los que el estudiante es elegible.
        List<PlanAction> filtered = plan.actions().stream()
                .map(a -> a.resourceId() != null && !ctx.eligibleResources().contains(a.resourceId())
                        ? ("APOYO_TRANSPORTE".equals(a.type()) ? null : a.withResource(null)) : a)
                .filter(java.util.Objects::nonNull).toList();
        return new PlanTemplate(filtered, plan.studentMessage());
    }
}

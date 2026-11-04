package co.edu.retencion.ai;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redacta con IA un plan de apoyo para que el estudiante no pierda una materia por inasistencia.
 * Es una propuesta: un humano la aprueba. El mensaje al estudiante usa marcadores
 * ({@link StudentTextPlaceholders}) que el backend rellena; la IA nunca recibe el nombre.
 */
public interface SupportPlanWriter {

    /**
     * Nunca lanza por fallos de la IA: devuelve el borrador vacío y el motivo.
     *
     * @param payload          payload con {@code plan} (materia y nivel), construido por el {@link Anonymizer}
     * @param citableResources ids del catálogo que la IA puede citar
     */
    Outcome draft(Anonymizer.AnonymizedPayload payload, Set<String> citableResources);

    record Outcome(Optional<Draft> draft, AiCallInfo call) {
    }

    /**
     * @param actions        acciones con prioridad y responsable sugerido
     * @param studentMessage texto para el estudiante con marcadores
     */
    record Draft(List<Action> actions, String studentMessage) {
    }

    /**
     * @param type           NIVELACION, TUTORIA, AJUSTE_HORARIO, APOYO_TRANSPORTE, CONTACTO_DOCENTE,
     *                       REMISION_BIENESTAR, APOYO_ECONOMICO, CONSEJERIA u OTRO
     * @param title          título corto
     * @param description    descripción
     * @param priority       ALTA, MEDIA o BAJA
     * @param suggestedOwner DOCENTE, CONSEJERO, PSICOLOGO o COORDINADOR
     * @param resourceId     recurso del catálogo o {@code null}
     * @param dueInDays      plazo sugerido en días
     */
    record Action(String type, String title, String description, String priority, String suggestedOwner,
                  String resourceId, int dueInDays) {
    }
}

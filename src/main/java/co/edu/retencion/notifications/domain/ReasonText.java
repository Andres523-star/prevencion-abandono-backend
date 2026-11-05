package co.edu.retencion.notifications.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Texto legible de una notificación generado por plantilla desde el motivo estructurado (servicio de
 * dominio puro). Para el docente solo hay plantillas académicas (notas y faltas), p. ej. "Bajó su
 * promedio a 2.6 en Anatomía y tiene 3 faltas".
 */
public final class ReasonText {

    private ReasonText() {
    }

    public static String of(Notification.Type type, Map<String, Object> reason) {
        return switch (type) {
            case RIESGO_ESTUDIANTE -> academic(reason);
            case ALERTA_ASIGNADA -> "Se te asignó una alerta de riesgo " + level(reason) + kind(reason);
            case ALERTA_ESCALADA -> "Se escaló a ti una alerta de riesgo " + level(reason) + " sin atender" + kind(reason);
            case JUSTIFICACION_PENDIENTE -> "Hay una justificación de falta pendiente de tu revisión" + in(reason);
            case JUSTIFICACION_DECIDIDA -> "Tu justificación" + in(reason) + " fue "
                    + (Boolean.TRUE.equals(reason.get("approved")) ? "aprobada" : "rechazada");
            case NUEVO_MENSAJE -> "Tienes un mensaje nuevo en el chat";
            case INTERVENCION_ASIGNADA -> "Se te asignó una intervención de " + String.valueOf(reason.getOrDefault("type", "apoyo"))
                    .toLowerCase().replace('_', ' ');
            case SEGUIMIENTO_RETENCION -> "Seguimiento de retención a " + reason.getOrDefault("offsetDays", "") + " días pendiente";
            case CAMPANA_REINGRESO -> "Contacto de reingreso pendiente con un estudiante retirado o aplazado";
        };
    }

    /** "Bajó su promedio a 2.6 en Anatomía y tiene 3 faltas" (solo notas y faltas). */
    static String academic(Map<String, Object> r) {
        String subject = r.get("subject") == null ? "la materia" : r.get("subject").toString();
        List<String> parts = new ArrayList<>();
        BigDecimal average = r.get("average") == null ? null : new BigDecimal(r.get("average").toString());
        if (average != null && "BAJA".equals(r.get("trend"))) {
            parts.add("Bajó su promedio a " + one(average) + " en " + subject);
        } else if (average != null && average.compareTo(new BigDecimal("3.0")) < 0) {
            parts.add("Tiene promedio de " + one(average) + " en " + subject);
        }
        int absences = r.get("absences") instanceof Number n ? n.intValue() : 0;
        if (absences > 0) {
            String faltas = absences + (absences == 1 ? " falta" : " faltas");
            Object max = r.get("maxAbsences");
            String limit = max instanceof Number m ? " (límite " + m.intValue() + ")" : "";
            parts.add(parts.isEmpty() ? "Tiene " + faltas + " en " + subject + limit : "tiene " + faltas + limit);
        }
        return parts.isEmpty() ? "Se detectó riesgo académico en " + subject : String.join(" y ", parts);
    }

    private static String one(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String level(Map<String, Object> r) {
        return String.valueOf(r.getOrDefault("level", "")).toUpperCase();
    }

    private static String kind(Map<String, Object> r) {
        Object k = r.get("kind");
        return "ASISTENCIA".equals(k) ? " por asistencia" : "PERDIDA".equals(k) ? " por pérdida de materia" : "";
    }

    private static String in(Map<String, Object> r) {
        return r.get("subject") == null ? "" : " de " + r.get("subject");
    }
}

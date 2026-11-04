package co.edu.retencion.mailing.domain;

/**
 * Tipos de aviso con su plantilla Thymeleaf ({@code templates/mail/<file>.html}) y su asunto.
 * El asunto solo menciona la materia: nada de riesgo, salud ni bienestar.
 */
public enum MailTemplate {

    PRIMERA_FALTA("primera-falta", "Se registró una falta en %s"),
    FALTA_REGISTRADA("falta-registrada", "Se registró una falta en %s"),
    AVISO("aviso", "Aviso de asistencia en %s"),
    CRITICO("critico", "Aviso importante de asistencia en %s"),
    PERDIDA("perdida", "Alcanzaste el límite de faltas en %s"),
    JUSTIFICACION_APROBADA("justificacion-aprobada", "Tu justificación en %s fue aprobada"),
    JUSTIFICACION_RECHAZADA("justificacion-rechazada", "Tu justificación en %s fue revisada"),
    RESUMEN_DIARIO("resumen-diario", "Resumen de faltas registradas");

    private final String file;
    private final String subjectPattern;

    MailTemplate(String file, String subjectPattern) {
        this.file = file;
        this.subjectPattern = subjectPattern;
    }

    public String view() {
        return "mail/" + file;
    }

    public String subject(String subjectName) {
        return subjectPattern.formatted(subjectName == null ? "" : subjectName);
    }

    /** Avisos derivados de una falta (la plantilla final se elige al enviar, según el contador). */
    public boolean isAbsenceNotice() {
        return this == PRIMERA_FALTA || this == FALTA_REGISTRADA || this == AVISO || this == CRITICO || this == PERDIDA;
    }
}

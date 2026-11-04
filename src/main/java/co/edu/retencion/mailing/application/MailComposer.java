package co.edu.retencion.mailing.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import co.edu.retencion.mailing.domain.MailTemplate;

/**
 * Renderiza las plantillas Thymeleaf de {@code templates/mail/}. Solo recibe hechos académicos
 * (materia, fechas, contador, límite, plazo) y el enlace al portal: el modelo no tiene forma de
 * llevar información de riesgo, salud o bienestar al correo.
 */
@Component
class MailComposer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TemplateEngine templates;
    private final MailProperties properties;

    MailComposer(TemplateEngine templates, MailProperties properties) {
        this.templates = templates;
        this.properties = properties;
    }

    record Composed(String subject, String html) {
    }

    Composed compose(MailTemplate template, String subjectName, Map<String, Object> variables) {
        Context context = new Context(new java.util.Locale("es", "CO"));
        context.setVariables(variables);
        context.setVariable("materia", subjectName);
        context.setVariable("portalUrl", properties.portalUrl());
        return new Composed(template.subject(subjectName), templates.process(template.view(), context));
    }

    static String date(String isoDate) {
        return isoDate == null ? null : LocalDate.parse(isoDate).format(DATE);
    }
}

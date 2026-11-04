package co.edu.retencion.ai.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lista blanca de campos que pueden salir hacia la IA (nunca lista negra: un campo nuevo queda
 * bloqueado hasta que alguien lo agregue aquí a conciencia). Las rutas usan punto para objetos y
 * {@code []} para elementos de arreglo, p. ej. {@code subjects[].average}.
 *
 * <p>Además de la estructura, revisa cada texto con {@link PiiRedactor#detect}: si aparece un correo,
 * un teléfono, un número de documento o un nombre del diccionario, el payload se rechaza (defensa
 * en profundidad: el texto libre ya debió llegar redactado).
 */
public final class PayloadWhitelist {

    /** Formato del código seudónimo {@code anon_code}. */
    public static final Pattern ANON_CODE = Pattern.compile("^EST-[0-9A-HJKMNP-TV-Z]{10}$");

    /** Campos permitidos en el payload del análisis de riesgo, del plan de apoyo y del agente. */
    public static final Set<String> RISK_PAYLOAD = Set.of(
            "schemaVersion", "caseId", "program", "semester",
            "academic", "academic.semesterAverage", "academic.semesterAverageBand", "academic.subjectsBelowPassing",
            "academic.subjectCount", "academic.academicObservations", "academic.conductObservations",
            "subjects", "subjects[]", "subjects[].ref", "subjects[].name", "subjects[].average",
            "subjects[].averageBand", "subjects[].gradeCount", "subjects[].trend", "subjects[].countedAbsences",
            "subjects[].maxAbsences", "subjects[].attendanceLevel", "subjects[].tardies",
            "subjects[].pendingJustifications",
            "openCauses", "openCauses[]", "openCauses[].code", "openCauses[].category",
            "context", "context.works", "context.dependentsBand", "context.transport",
            "wellbeing", "wellbeing.openReferrals", "wellbeing.assessments",
            "riskScore", "riskScore.score", "riskScore.scoreType", "riskScore.level", "riskScore.factors",
            "riskScore.factors[]", "riskScore.factors[].code", "riskScore.factors[].category",
            "riskScore.factors[].points", "riskScore.factors[].explanation",
            "observations", "observations[]",
            "resources", "resources[]", "resources[].id", "resources[].name", "resources[].category",
            "resources[].description", "resources[].eligibility",
            "plan", "plan.subjectRef", "plan.attendanceLevel",
            // Salidas de herramientas del agente de retención.
            "history", "history[]", "history[].daysAgo", "history[].level", "history[].score",
            "history[].scoreType", "history[].source", "history[].factorCodes", "history[].factorCodes[]");

    /** Campos cuyo valor no se revisa con el detector de PII (formato propio validado aparte). */
    private static final Set<String> FORMAT_CHECKED = Set.of("caseId", "schemaVersion");

    private final Set<String> allowed;
    private final PiiRedactor detector;

    public PayloadWhitelist(Set<String> allowed, PiiRedactor detector) {
        this.allowed = Set.copyOf(allowed);
        this.detector = detector;
    }

    /** Violación de la política de payload: no se envía nada a la IA. */
    public static final class PayloadPolicyException extends RuntimeException {

        private final List<String> violations;

        public PayloadPolicyException(List<String> violations) {
            // El mensaje lleva rutas y tipos de hallazgo, nunca los valores.
            super("Payload rechazado por la política de privacidad: " + violations);
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() {
            return violations;
        }
    }

    /** Lanza {@link PayloadPolicyException} si hay campos fuera de la lista o PII en algún texto. */
    public void validate(JsonNode root) {
        List<String> violations = new ArrayList<>();
        walk(root, "", violations);
        JsonNode caseId = root.path("caseId");
        if (!caseId.isMissingNode() && !(caseId.isTextual() && ANON_CODE.matcher(caseId.asText()).matches())) {
            violations.add("caseId: no es un anon_code");
        }
        if (!violations.isEmpty()) {
            throw new PayloadPolicyException(violations);
        }
    }

    private void walk(JsonNode node, String path, List<String> violations) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String child = path.isEmpty() ? field.getKey() : path + "." + field.getKey();
                if (!allowed.contains(child)) {
                    violations.add(child + ": campo fuera de la lista blanca");
                    continue;
                }
                walk(field.getValue(), child, violations);
            }
        } else if (node.isArray()) {
            String child = path + "[]";
            if (!allowed.contains(child)) {
                violations.add(child + ": arreglo fuera de la lista blanca");
                return;
            }
            node.forEach(element -> walk(element, child, violations));
        } else if (node.isTextual() && !FORMAT_CHECKED.contains(path)) {
            detector.detect(node.asText()).forEach(kind -> violations.add(path + ": contiene " + kind));
        }
    }
}

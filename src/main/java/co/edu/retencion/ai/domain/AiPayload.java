package co.edu.retencion.ai.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import co.edu.retencion.ai.Anonymizer.PlanTarget;
import co.edu.retencion.ai.CaseFacts;
import co.edu.retencion.ai.RetentionAgentTools.HistoryEntry;
import co.edu.retencion.ai.SupportResourceInfo;

/**
 * Payload seudonimizado hacia la IA.
 *
 * <p><b>Patrón Builder:</b> el payload tiene muchas partes opcionales (señales sensibles, texto
 * libre, plan, historial, recursos) que dependen de la configuración. El builder las compone en un
 * orden fijo (JSON canónico, así el hash es estable), redacta cada texto y en {@link Builder#build()}
 * valida el resultado contra la {@link PayloadWhitelist}: no existe forma de obtener un payload que
 * no haya pasado la validación.
 *
 * @param json   JSON canónico
 * @param sha256 hash hexadecimal del JSON (trazabilidad y detección de cambios)
 */
public record AiPayload(String json, String sha256) {

    public static final String SCHEMA_VERSION = "risk-payload.v1";

    @Override
    public String toString() {
        return "AiPayload[sha256=" + sha256 + "]";
    }

    public static Builder builder(ObjectMapper mapper, PiiRedactor redactor, PayloadWhitelist whitelist) {
        return new Builder(mapper, redactor, whitelist);
    }

    /** Construye el payload campo por campo; ver la clase. */
    public static final class Builder {

        private final ObjectMapper mapper;
        private final PiiRedactor redactor;
        private final PayloadWhitelist whitelist;
        private final ObjectNode root;
        private boolean sensitive;
        private boolean freeText;

        private Builder(ObjectMapper mapper, PiiRedactor redactor, PayloadWhitelist whitelist) {
            this.mapper = mapper;
            this.redactor = redactor;
            this.whitelist = whitelist;
            this.root = mapper.createObjectNode();
            root.put("schemaVersion", SCHEMA_VERSION);
        }

        /** Hechos académicos, de asistencia, causas no sensibles, contexto y puntaje. */
        public Builder facts(CaseFacts facts, boolean includeSensitive) {
            root.put("caseId", facts.anonCode());
            root.put("program", facts.programCode());
            root.put("semester", facts.semester());

            ObjectNode academic = root.putObject("academic");
            putDecimal(academic, "semesterAverage", facts.semesterAverage());
            academic.put("semesterAverageBand", averageBand(facts.semesterAverage()));
            academic.put("subjectsBelowPassing", facts.subjectsBelowPassing());
            academic.put("subjectCount", facts.subjects().size());
            academic.put("academicObservations", facts.academicObservations());
            academic.put("conductObservations", facts.conductObservations());

            ArrayNode subjects = root.putArray("subjects");
            for (CaseFacts.SubjectFacts s : facts.subjects()) {
                ObjectNode n = subjects.addObject();
                n.put("ref", s.ref());
                n.put("name", clean(s.subjectName()));
                putDecimal(n, "average", s.average());
                n.put("averageBand", averageBand(s.average()));
                n.put("gradeCount", s.gradeCount());
                n.put("trend", s.trend());
                n.put("countedAbsences", s.countedAbsences());
                n.put("maxAbsences", s.maxAbsences());
                n.put("attendanceLevel", s.attendanceLevel());
                n.put("tardies", s.tardies());
                n.put("pendingJustifications", s.pendingJustifications());
            }

            ArrayNode causes = root.putArray("openCauses");
            for (CaseFacts.CauseFacts c : facts.openCauses()) {
                // Las causas de salud son dato sensible: solo con consentimiento expreso configurado.
                if ("SALUD".equals(c.category()) && !includeSensitive) {
                    continue;
                }
                sensitive |= "SALUD".equals(c.category());
                ObjectNode n = causes.addObject();
                n.put("code", c.code());
                n.put("category", c.category());
            }

            if (facts.context() != null) {
                ObjectNode context = root.putObject("context");
                if (facts.context().works() != null) {
                    context.put("works", facts.context().works());
                }
                if (facts.context().dependents() != null) {
                    context.put("dependentsBand", dependentsBand(facts.context().dependents()));
                }
                if (facts.context().transport() != null) {
                    context.put("transport", facts.context().transport());
                }
            }

            if (includeSensitive && facts.wellbeing() != null) {
                ObjectNode wellbeing = root.putObject("wellbeing");
                wellbeing.put("openReferrals", facts.wellbeing().openReferrals());
                wellbeing.put("assessments", facts.wellbeing().assessments());
                sensitive = true;
            }

            if (facts.score() != null) {
                ObjectNode score = root.putObject("riskScore");
                putDecimal(score, "score", facts.score().score());
                score.put("scoreType", facts.score().scoreType());
                score.put("level", facts.score().level());
                ArrayNode factors = score.putArray("factors");
                for (CaseFacts.FactorFacts f : facts.score().factors()) {
                    if ("BIENESTAR".equals(f.category()) && !includeSensitive) {
                        continue;
                    }
                    ObjectNode n = factors.addObject();
                    n.put("code", f.code());
                    n.put("category", f.category());
                    putDecimal(n, "points", f.points());
                    n.put("explanation", clean(f.explanation()));
                }
            }
            return this;
        }

        /** Texto libre: SIEMPRE redactado; solo se llama si la configuración lo permite. */
        public Builder freeTexts(List<String> texts) {
            if (texts.isEmpty()) {
                return this;
            }
            ArrayNode observations = root.putArray("observations");
            texts.stream().map(t -> t.length() > 1000 ? t.substring(0, 1000) : t)
                    .map(redactor::redact).forEach(observations::add);
            freeText = true;
            return this;
        }

        /** Recursos elegibles del catálogo (la IA solo puede citar estos ids). */
        public Builder resources(List<SupportResourceInfo> resources) {
            ArrayNode array = root.putArray("resources");
            resources.forEach(r -> {
                ObjectNode n = array.addObject();
                n.put("id", r.id());
                n.put("name", clean(r.name()));
                n.put("category", r.category());
                n.put("description", clean(r.description()));
                n.put("eligibility", clean(r.eligibilityText()));
            });
            return this;
        }

        public Builder plan(PlanTarget target) {
            if (target != null) {
                ObjectNode plan = root.putObject("plan");
                plan.put("subjectRef", target.subjectRef());
                plan.put("attendanceLevel", target.attendanceLevel());
            }
            return this;
        }

        /** Historial de riesgo (herramienta del agente); la fecha sale como "hace N días". */
        public Builder history(List<HistoryEntry> entries, java.time.Instant now) {
            ArrayNode array = root.putArray("history");
            entries.forEach(h -> {
                ObjectNode n = array.addObject();
                n.put("daysAgo", Math.max(0, java.time.Duration.between(h.assessedAt(), now).toDays()));
                n.put("level", h.level());
                putDecimal(n, "score", h.score());
                n.put("scoreType", h.scoreType());
                n.put("source", h.source());
                ArrayNode codes = n.putArray("factorCodes");
                h.factorCodes().forEach(codes::add);
            });
            return this;
        }

        public boolean sensitive() {
            return sensitive;
        }

        public boolean freeTextIncluded() {
            return freeText;
        }

        /** Valida contra la lista blanca y calcula el hash. */
        public AiPayload build() {
            whitelist.validate(root);
            try {
                String json = mapper.writeValueAsString(root);
                return new AiPayload(json, sha256(json));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("No se pudo serializar el payload", ex);
            }
        }

        private String clean(String text) {
            return text == null ? null : redactor.redact(text);
        }
    }

    /** Banda del promedio: un rango comunica lo mismo a la IA con menos detalle. */
    public static String averageBand(BigDecimal average) {
        if (average == null) {
            return "SIN_NOTAS";
        }
        double v = average.doubleValue();
        if (v < 2.0) {
            return "<2.0";
        }
        if (v < 2.5) {
            return "2.0-2.4";
        }
        if (v < 3.0) {
            return "2.5-2.9";
        }
        if (v < 3.5) {
            return "3.0-3.4";
        }
        if (v < 4.0) {
            return "3.5-3.9";
        }
        if (v < 4.5) {
            return "4.0-4.4";
        }
        return "4.5-5.0";
    }

    public static String dependentsBand(int dependents) {
        if (dependents <= 0) {
            return "0";
        }
        return dependents <= 2 ? "1-2" : "3+";
    }

    public static String sha256(String json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.setScale(2, RoundingMode.HALF_UP));
        }
    }
}

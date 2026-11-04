package co.edu.retencion.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import co.edu.retencion.ai.Anonymizer.PlanTarget;
import co.edu.retencion.ai.CaseFacts;
import co.edu.retencion.ai.RetentionAgentTools.HistoryEntry;
import co.edu.retencion.ai.StudentTextPlaceholders;
import co.edu.retencion.ai.SupportResourceInfo;
import co.edu.retencion.ai.domain.PayloadWhitelist.PayloadPolicyException;

/**
 * Pruebas obligatorias del anonimizador: ningún payload hacia la IA contiene PII, solo salen campos
 * de la lista blanca y el texto libre siempre sale redactado.
 */
class AiDomainTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final PiiRedactor REDACTOR = redactor();
    static final PayloadWhitelist WHITELIST = new PayloadWhitelist(PayloadWhitelist.RISK_PAYLOAD, REDACTOR);

    /** Identidad de un estudiante de prueba: nada de esto puede aparecer en un payload. */
    static final String FULL_NAME = "Juan Camilo Pérez Gómez";
    static final String DOCUMENT = "1020304050";
    static final String EMAIL = "juan.perez@estudiantes.universidad.edu.co";
    static final String PHONE = "300 123 4567";

    /** Carga el mismo diccionario que la aplicación. */
    static PiiRedactor redactor() {
        Set<String> names = new HashSet<>();
        Set<String> ambiguous = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                AiDomainTest.class.getResourceAsStream("/ai/nombres-comunes.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.strip();
                if (w.isEmpty() || w.startsWith("#")) {
                    continue;
                }
                if (w.endsWith("*")) {
                    w = w.substring(0, w.length() - 1);
                    ambiguous.add(w);
                }
                names.add(w);
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return new PiiRedactor(names, ambiguous);
    }

    static CaseFacts facts(List<String> freeTexts) {
        return new CaseFacts("EST-7K2M9QX4TB", "ISW", 3, new BigDecimal("2.70"), 1,
                List.of(new CaseFacts.SubjectFacts("M1", "Cálculo Diferencial", new BigDecimal("2.60"), 3, "BAJA", 3, 4,
                        "CRITICO", 1, 0)),
                1, 2, List.of(new CaseFacts.CauseFacts("NECESIDAD_DE_TRABAJAR", "ECONOMICA"),
                new CaseFacts.CauseFacts("SALUD_MENTAL", "SALUD")),
                new CaseFacts.ContextFacts(true, 2, "TRANSPORTE_PUBLICO"), new CaseFacts.WellbeingFacts(1, 2),
                new CaseFacts.ScoreFacts(new BigDecimal("63.00"), "RULES", "ALTO", List.of(
                        new CaseFacts.FactorFacts("ABSENCES", "ASISTENCIA", new BigDecimal("15"),
                                "Cálculo Diferencial: 3 de 4 faltas (nivel crítico)"),
                        new CaseFacts.FactorFacts("WELLBEING", "BIENESTAR", new BigDecimal("8"),
                                "Tiene remisiones de bienestar abiertas"))),
                freeTexts);
    }

    static List<SupportResourceInfo> resources() {
        return List.of(new SupportResourceInfo("TUT-ACADEMICA", "Tutorías académicas", "TUTORIA",
                "Sesiones con docentes tutores", "Todos", "tutorias@universidad.edu.co - ext. 2101"));
    }

    // ------------------------------------------------------------------ redactor

    @Test
    void redactorRemovesEmailsPhonesDocumentsUrlsHandlesAndNames() {
        String text = "Hablé con " + FULL_NAME + " (CC " + DOCUMENT + ", cel " + PHONE + ", " + EMAIL
                + "). Su mamá Martha llamó al +57 310-555-1234. Ver https://drive.example.com/x y @juanp. "
                + "Documento 1.020.304.050.";
        String redacted = REDACTOR.redact(text);
        assertThat(redacted).doesNotContain("Juan", "Camilo", "Pérez", "Gómez", "Martha", DOCUMENT, "123 4567",
                "555-1234", "juan.perez", "https://", "@juanp", "1.020.304.050");
        assertThat(redacted).contains(PiiRedactor.NAME_TOKEN, PiiRedactor.NUMBER_TOKEN, PiiRedactor.EMAIL_TOKEN,
                PiiRedactor.URL_TOKEN, PiiRedactor.HANDLE_TOKEN);
        assertThat(REDACTOR.detect(redacted)).isEmpty();
        assertThat(REDACTOR.detect(text)).contains("NOMBRE", "TELEFONO_O_DOCUMENTO", "CORREO", "ENLACE", "USUARIO");
    }

    @Test
    void ambiguousDictionaryWordsAreOnlyRedactedWhenCapitalized() {
        assertThat(REDACTOR.redact("Estudia a la luz de la luna y paga la mora")).isEqualTo(
                "Estudia a la luz de la luna y paga la mora");
        assertThat(REDACTOR.redact("Habló Luz Mora")).isEqualTo("Habló " + PiiRedactor.NAME_TOKEN);
        // Los nombres no ambiguos se redactan aunque estén en minúscula.
        assertThat(REDACTOR.redact("dijo juan que no viene")).doesNotContain("juan");
        // Números cortos (notas, faltas, extensiones) no se tocan.
        assertThat(REDACTOR.redact("3 de 4 faltas, promedio 2.6, ext. 2101")).isEqualTo("3 de 4 faltas, promedio 2.6, ext. 2101");
    }

    // ------------------------------------------------------------------ lista blanca

    @Test
    void whitelistRejectsUnknownFieldsAndPiiInValues() {
        ObjectNode ok = MAPPER.createObjectNode().put("caseId", "EST-7K2M9QX4TB").put("program", "ISW");
        WHITELIST.validate(ok);

        ObjectNode extra = ok.deepCopy();
        extra.put("fullName", "Juan Pérez");
        extra.putObject("context").put("stratum", 2);
        assertThatThrownBy(() -> WHITELIST.validate(extra)).isInstanceOf(PayloadPolicyException.class)
                .satisfies(ex -> assertThat(((PayloadPolicyException) ex).violations())
                        .anyMatch(v -> v.startsWith("fullName"))
                        .anyMatch(v -> v.startsWith("context.stratum"))
                        // El mensaje nunca incluye el valor.
                        .noneMatch(v -> v.contains("Juan")));

        ObjectNode piiValue = ok.deepCopy();
        piiValue.putArray("observations").add("llamar al 3001234567");
        assertThatThrownBy(() -> WHITELIST.validate(piiValue)).isInstanceOf(PayloadPolicyException.class);

        ObjectNode badCode = MAPPER.createObjectNode().put("caseId", DOCUMENT);
        assertThatThrownBy(() -> WHITELIST.validate(badCode)).isInstanceOf(PayloadPolicyException.class);
    }

    // ------------------------------------------------------------------ payload

    @Test
    void payloadHasOnlyWhitelistedFieldsNoStratumNoPiiAndNoSensitiveSignalsByDefault() throws Exception {
        String note = FULL_NAME + " dice que trabaja de noche; su correo es " + EMAIL + " y su celular " + PHONE;
        AiPayload.Builder builder = AiPayload.builder(MAPPER, REDACTOR, WHITELIST).facts(facts(List.of(note)), false)
                .freeTexts(List.of(note)).resources(resources()).plan(new PlanTarget("M1", "CRITICO"));
        AiPayload payload = builder.build();
        JsonNode root = MAPPER.readTree(payload.json());

        assertThat(paths(root)).allMatch(PayloadWhitelist.RISK_PAYLOAD::contains);
        assertThat(payload.json()).doesNotContain("Juan", "Camilo", "Pérez", "Gómez", DOCUMENT, EMAIL, "123 4567",
                "stratum", "estrato");
        // Señales sensibles fuera por defecto: ni bienestar, ni causa de salud, ni factor de bienestar.
        assertThat(root.has("wellbeing")).isFalse();
        assertThat(payload.json()).doesNotContain("SALUD_MENTAL", "WELLBEING");
        assertThat(builder.sensitive()).isFalse();
        assertThat(builder.freeTextIncluded()).isTrue();
        assertThat(root.path("observations").get(0).asText()).contains(PiiRedactor.NAME_TOKEN, PiiRedactor.EMAIL_TOKEN);
        assertThat(root.path("academic").path("semesterAverageBand").asText()).isEqualTo("2.5-2.9");
        assertThat(root.path("context").path("dependentsBand").asText()).isEqualTo("1-2");
        // Recursos sin contacto (no hace falta para recomendar).
        assertThat(payload.json()).doesNotContain("tutorias@");
        // Hash estable: el mismo caso produce el mismo payload.
        assertThat(AiPayload.builder(MAPPER, REDACTOR, WHITELIST).facts(facts(List.of(note)), false)
                .freeTexts(List.of(note)).resources(resources()).plan(new PlanTarget("M1", "CRITICO")).build().sha256())
                .isEqualTo(payload.sha256());
    }

    @Test
    void sensitiveSignalsOnlyWithExplicitConfiguration() throws Exception {
        AiPayload.Builder builder = AiPayload.builder(MAPPER, REDACTOR, WHITELIST).facts(facts(List.of()), true);
        JsonNode root = MAPPER.readTree(builder.build().json());
        assertThat(builder.sensitive()).isTrue();
        assertThat(root.path("wellbeing").path("openReferrals").asLong()).isEqualTo(1);
        assertThat(root.toString()).contains("SALUD_MENTAL");
    }

    @Test
    void historyPayloadUsesRelativeDays() throws Exception {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        JsonNode root = MAPPER.readTree(AiPayload.builder(MAPPER, REDACTOR, WHITELIST)
                .history(List.of(new HistoryEntry(now.minusSeconds(3 * 86_400), "ALTO", new BigDecimal("61"), "RULES",
                        "HYBRID", List.of("ABSENCES"))), now).build().json());
        assertThat(root.path("history").get(0).path("daysAgo").asInt()).isEqualTo(3);
        assertThat(AiPayload.averageBand(null)).isEqualTo("SIN_NOTAS");
        assertThat(AiPayload.averageBand(new BigDecimal("1.5"))).isEqualTo("<2.0");
        assertThat(AiPayload.averageBand(new BigDecimal("4.7"))).isEqualTo("4.5-5.0");
        assertThat(AiPayload.dependentsBand(0)).isEqualTo("0");
        assertThat(AiPayload.dependentsBand(5)).isEqualTo("3+");
    }

    @Test
    void placeholdersAreRestrictedAndRenderedOnlyInBackend() {
        assertThat(StudentTextPlaceholders.onlyAllowed("Hola {{nombre}}, ánimo en {{ materia }}")).isTrue();
        assertThat(StudentTextPlaceholders.onlyAllowed("Hola {{documento}}")).isFalse();
        assertThat(StudentTextPlaceholders.render("Hola {{nombre}}, ánimo en {{materia}} {{x}}", "Ana", "Química"))
                .isEqualTo("Hola Ana, ánimo en Química ");
        assertThat(StudentTextPlaceholders.render("Hola {{nombre}}", null, null)).isEqualTo("Hola estudiante");
    }

    @Test
    void eligibilityRulesAreEvaluatedInBackend() {
        SupportResource.Eligibility transport = new SupportResource.Eligibility(null, 3, null, null,
                List.of("TRANSPORTE_PUBLICO"), null);
        assertThat(transport.admits("ISW", 2, 2, false, "TRANSPORTE_PUBLICO")).isTrue();
        assertThat(transport.admits("ISW", 2, 5, false, "TRANSPORTE_PUBLICO")).isFalse();
        assertThat(transport.admits("ISW", 2, 2, false, "CARRO")).isFalse();
        SupportResource.Eligibility levelling = new SupportResource.Eligibility(List.of("MED"), null, 1, 4, null, true);
        assertThat(levelling.admits("MED", 3, null, true, null)).isTrue();
        assertThat(levelling.admits("DER", 3, null, true, null)).isFalse();
        assertThat(levelling.admits("MED", 6, null, true, null)).isFalse();
        assertThat(levelling.admits("MED", 3, null, false, null)).isFalse();
        SupportResource r = SupportResource.create("TUT-X", "Tutoría", SupportResource.Category.TUTORIA, "d", null, "t",
                "c", Instant.now());
        assertThat(r.getEligibility()).isEqualTo("{}");
        assertThatThrownBy(() -> SupportResource.create("tut x", "n", SupportResource.Category.OTRO, "d", "{}", "t", "c",
                Instant.now())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void agentRunAndCallRecordsKeepTraceability() {
        AgentRun run = AgentRun.start(java.util.UUID.randomUUID(), "claude-opus-5-5", "retention-agent.v1",
                java.util.UUID.randomUUID(), Instant.now());
        run.toolCalled();
        run.addTokens(100, 20);
        run.completeWithDraft("{}", "claude-opus-5-5", Instant.now());
        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.BORRADOR);
        assertThat(run.getToolCalls()).isEqualTo(1);
        AgentRun failed = AgentRun.start(java.util.UUID.randomUUID(), "m", "p", java.util.UUID.randomUUID(), Instant.now());
        failed.fail("TIMEOUT", Instant.now());
        assertThat(failed.getStatus()).isEqualTo(AgentRun.Status.FALLIDO);
        AiCall call = AiCall.of("RISK_EXPLAIN", "m", "p", "h", "OK", 1, 10, 5, 3, Instant.now());
        assertThat(call.getOutcome()).isEqualTo("OK");
        AgentToolCall tool = AgentToolCall.of(run.getId(), 1, "get_attendance", "{}", "h", 2, false, Instant.now());
        assertThat(tool.getSeq()).isEqualTo(1);
    }

    static List<String> paths(JsonNode root) {
        List<String> out = new ArrayList<>();
        collect(root, "", out);
        return out;
    }

    private static void collect(JsonNode node, String path, List<String> out) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                out.add(child);
                collect(e.getValue(), child, out);
            }
        } else if (node.isArray()) {
            out.add(path + "[]");
            node.forEach(n -> collect(n, path + "[]", out));
        }
    }
}

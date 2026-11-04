package co.edu.retencion.risk.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.RecordsSnapshot;
import co.edu.retencion.records.RecordsSnapshot.OpenCause;
import co.edu.retencion.records.RecordsSnapshot.SubjectSnapshot;
import co.edu.retencion.risk.RiskLevel;
import co.edu.retencion.risk.ScoreType;
import co.edu.retencion.risk.domain.Factor.FactorCategory;
import co.edu.retencion.risk.domain.RiskScorer.LevelThresholds;
import co.edu.retencion.risk.domain.RiskScorer.ScoreResult;
import co.edu.retencion.students.SocioeconomicInfo;
import co.edu.retencion.students.TransportMode;
import co.edu.retencion.wellbeing.WellbeingSignals;

class RiskDomainTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final LevelThresholds LEVELS = new LevelThresholds(new BigDecimal("30"), new BigDecimal("60"));

    static SubjectSnapshot subject(String name, String average, String trend, int absences, AttendanceLevel level,
                                   int tardies) {
        BigDecimal avg = average == null ? null : new BigDecimal(average);
        return new SubjectSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), name, avg,
                avg == null ? 0 : 3, avg != null && avg.compareTo(new BigDecimal("3.0")) < 0, trend, absences, absences,
                0, tardies, 4, 2, 3, level);
    }

    static RiskFeatures features(SocioeconomicInfo socio, long openReferrals, List<OpenCause> causes,
                                 SubjectSnapshot... subjects) {
        List<SubjectSnapshot> list = List.of(subjects);
        BigDecimal avg = list.stream().map(SubjectSnapshot::average).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long withGrades = list.stream().filter(s -> s.average() != null).count();
        BigDecimal semester = withGrades == 0 ? null
                : avg.divide(BigDecimal.valueOf(withGrades), 2, java.math.RoundingMode.HALF_UP);
        int below = (int) list.stream().filter(SubjectSnapshot::belowPassing).count();
        RecordsSnapshot records = new RecordsSnapshot(UUID.randomUUID(), UUID.randomUUID(), list, semester, below, 1, 2,
                causes);
        return new RiskFeatures(UUID.randomUUID(), "EST-7K2M9QX4TB", UUID.randomUUID(), "ISW", 3, records, socio,
                new WellbeingSignals.Summary(1, 0, openReferrals));
    }

    static RiskFeatures atRisk(Integer stratum) {
        return features(new SocioeconomicInfo(stratum, true, 1, TransportMode.TRANSPORTE_PUBLICO), 0, List.of(),
                subject("Anatomía", "2.6", "BAJA", 3, AttendanceLevel.CRITICO, 1),
                subject("Química", "3.4", "ESTABLE", 0, AttendanceLevel.NINGUNO, 2));
    }

    static RulesScorer scorer(Map<String, JsonNode> config) {
        return new RulesScorer(RiskRules.all(), config, LEVELS);
    }

    @Test
    void rulesAddUpContributionsWithExplanations() {
        ScoreResult result = scorer(Map.of()).score(atRisk(2));
        assertThat(result.scoreType()).isEqualTo(ScoreType.RULES);
        assertThat(result.probability()).isNull();
        assertThat(result.factors()).extracting(Factor::code)
                .contains("FAILING_SUBJECTS", "ABSENCES", "GRADE_TREND", "TARDIES", "CONDUCT", "SOCIOECONOMIC_CONTEXT");
        Factor absences = result.factors().stream().filter(f -> f.code().equals("ABSENCES")).findFirst().orElseThrow();
        assertThat(absences.explanation()).isEqualTo("Anatomía: 3 de 4 faltas (nivel crítico)");
        assertThat(absences.details()).containsEntry("absences", 3).containsEntry("maxAbsences", 4);
        BigDecimal sum = result.factors().stream().map(Factor::points).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(result.score()).isEqualByComparingTo(sum.min(BigDecimal.valueOf(100)));
        assertThat(result.version()).startsWith("rules-");
    }

    /** Decisión de sesgo: el estrato NO castiga. Mismo caso, estratos 1 y 6 → mismo puntaje. */
    @Test
    void stratumNeverChangesTheScore() {
        ScoreResult low = scorer(Map.of()).score(atRisk(1));
        ScoreResult high = scorer(Map.of()).score(atRisk(6));
        ScoreResult unknown = scorer(Map.of()).score(atRisk(null));
        assertThat(low.score()).isEqualByComparingTo(high.score()).isEqualByComparingTo(unknown.score());
        assertThat(low.factors()).noneMatch(f -> f.explanation().toLowerCase().contains("estrato"));
        // Ni siquiera configurándolo: no existe parámetro que haga sumar el estrato.
        JsonNode attempt = MAPPER.createObjectNode().put("enabled", true).put("combinedPoints", 4)
                .put("stratumPoints", 50).put("maxPoints", 8);
        ScoreResult tried = scorer(Map.of("risk.rules.socioeconomic-context", attempt)).score(atRisk(1));
        assertThat(tried.score()).isEqualByComparingTo(scorer(Map.of()).score(atRisk(6)).score());
    }

    @Test
    void socioeconomicContextOnlyCountsWithAnAcademicOrAttendanceSignal() {
        RiskFeatures fine = features(new SocioeconomicInfo(1, true, 4, TransportMode.TRANSPORTE_PUBLICO), 0, List.of(),
                subject("Química", "4.2", "ESTABLE", 0, AttendanceLevel.NINGUNO, 0));
        ScoreResult result = scorer(Map.of()).score(fine);
        // Solo queda la conducta (2 observaciones); el contexto socioeconómico no suma sin señal académica.
        assertThat(result.factors()).extracting(Factor::code).containsExactly("CONDUCT");
        assertThat(result.level()).isEqualTo(RiskLevel.BAJO);
    }

    @Test
    void changingAThresholdInSettingsChangesTheResult() {
        RiskFeatures f = atRisk(3);
        ScoreResult before = scorer(Map.of()).score(f);
        JsonNode stricter = MAPPER.createObjectNode().put("enabled", true).put("warningPoints", 8)
                .put("criticalPoints", 35).put("lostPoints", 40).put("maxPoints", 40);
        ScoreResult after = scorer(Map.of("risk.rules.absences", stricter)).score(f);
        assertThat(after.score()).isGreaterThan(before.score());
        assertThat(after.version()).isNotEqualTo(before.version());
        JsonNode disabled = MAPPER.createObjectNode().put("enabled", false);
        assertThat(scorer(Map.of("risk.rules.absences", disabled)).score(f).factors())
                .noneMatch(x -> x.code().equals("ABSENCES"));
    }

    @Test
    void wellbeingAndHealthCausesAreSeparateSensitiveFactors() {
        RiskFeatures f = features(null, 2, List.of(new OpenCause("SALUD_MENTAL", "SALUD"),
                new OpenCause("NECESIDAD_DE_TRABAJAR", "ECONOMICA")),
                subject("Anatomía", "2.2", "BAJA", 4, AttendanceLevel.PERDIDA, 0));
        ScoreResult r = scorer(Map.of()).score(f);
        assertThat(r.factors()).filteredOn(x -> x.category() == FactorCategory.BIENESTAR).extracting(Factor::code)
                .containsExactlyInAnyOrder("WELLBEING", "HEALTH_CAUSES");
        assertThat(r.factors()).filteredOn(x -> x.code().equals("OPEN_CAUSES")).singleElement()
                .satisfies(x -> assertThat(x.explanation()).doesNotContain("SALUD"));
        assertThat(r.factors()).filteredOn(x -> x.code().equals("LOW_AVERAGE")).singleElement()
                .satisfies(x -> assertThat(x.points()).isEqualByComparingTo("25"));
        assertThat(r.level()).isEqualTo(RiskLevel.ALTO);
    }

    @Test
    void ruleSettingsFallBackToDefaultsOnTypos() {
        RuleSettings s = RuleSettings.of(MAPPER.createObjectNode().put("points", "quince").put("enabled", "si"));
        assertThat(s.enabled()).isTrue();
        assertThat(s.integer("points", 15)).isEqualTo(15);
        assertThat(s.decimal("threshold", "3.0")).isEqualByComparingTo("3.0");
        assertThat(RuleSettings.of(null).pointsFor("points", "SALUD", 6)).isEqualTo(6);
        assertThat(LEVELS.levelFor(new BigDecimal("29.99"))).isEqualTo(RiskLevel.BAJO);
        assertThat(LEVELS.levelFor(new BigDecimal("30"))).isEqualTo(RiskLevel.MEDIO);
        assertThat(LEVELS.levelFor(new BigDecimal("60"))).isEqualTo(RiskLevel.ALTO);
        assertThat(RiskLevel.ALTO.isAbove(RiskLevel.MEDIO)).isTrue();
        assertThat(RiskLevel.max(RiskLevel.BAJO, RiskLevel.MEDIO)).isEqualTo(RiskLevel.MEDIO);
    }

    // ------------------------------------------------------------------ ML

    @Test
    void mlFeatureContractRejectsSocioeconomicAndUnknownVariables() {
        assertThat(MlFeatureVector.validate(MlFeatureVector.FEATURES)).isEmpty();
        assertThat(MlFeatureVector.validate(List.of("semester_average", "stratum"))).singleElement()
                .satisfies(e -> assertThat(e).contains("prohibida"));
        assertThat(MlFeatureVector.validate(List.of("open_economic_causes"))).isNotEmpty();
        assertThat(MlFeatureVector.validate(List.of("works_hours"))).isNotEmpty();
        assertThat(MlFeatureVector.validate(List.of("favorite_color"))).singleElement()
                .satisfies(e -> assertThat(e).contains("desconocida"));
        assertThat(MlFeatureVector.validate(List.of("semester", "semester"))).contains("Variables repetidas");
        assertThat(MlFeatureVector.validate(List.of())).isNotEmpty();
        Map<String, Double> values = MlFeatureVector.values(3, atRisk(1).records());
        assertThat(values.keySet()).containsExactlyElementsOf(MlFeatureVector.FEATURES);
        assertThat(values.get("max_absence_ratio")).isEqualTo(0.75);
        assertThat(MlFeatureVector.vector(List.of("subject_count", "semester"), values)).containsExactly(2f, 3f);
        assertThatThrownBy(() -> MlFeatureVector.vector(List.of("x"), values)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mlScorerReturnsCalibratedProbabilityWithRuleFactors() {
        List<float[]> seen = new ArrayList<>();
        MlScorer.ProbabilityModel model = new MlScorer.ProbabilityModel() {
            @Override
            public String version() {
                return "lgbm-test";
            }

            @Override
            public List<String> features() {
                return List.of("max_absence_ratio", "subjects_below_passing");
            }

            @Override
            public double probability(float[] vector) {
                seen.add(vector);
                return 0.6731;
            }
        };
        ScoreResult r = new MlScorer(model, new LevelThresholds(new BigDecimal("0.3"), new BigDecimal("0.6")),
                scorer(Map.of())).score(atRisk(2));
        assertThat(r.scoreType()).isEqualTo(ScoreType.ML_CALIBRATED);
        assertThat(r.probability()).isEqualByComparingTo("0.6731");
        assertThat(r.score()).isEqualByComparingTo("67.31");
        assertThat(r.level()).isEqualTo(RiskLevel.ALTO);
        assertThat(r.version()).isEqualTo("lgbm-test");
        assertThat(r.rulesVersion()).startsWith("rules-");
        assertThat(r.factors()).isNotEmpty();
        assertThat(seen.get(0)).containsExactly(0.75f, 1f);
    }

    @Test
    void modelComparisonBlocksWorseModelsAndGroupGaps() throws Exception {
        JsonNode current = MAPPER.readTree("""
                {"auc":0.80,"recallHighRisk":0.70,"brier":0.15,"ece":0.03}""");
        JsonNode better = MAPPER.readTree("""
                {"auc":0.83,"recallHighRisk":0.72,"brier":0.14,"ece":0.02,
                 "byGroup":{"1-2":{"auc":0.82},"3-4":{"auc":0.84}}}""");
        JsonNode worse = MAPPER.readTree("""
                {"auc":0.76,"recallHighRisk":0.60,"brier":0.18,"ece":0.08}""");
        JsonNode unfair = MAPPER.readTree("""
                {"auc":0.85,"byGroup":{"1-2":{"auc":0.70},"5-6":{"auc":0.90}}}""");
        assertThat(ModelComparison.compare(better, current).verdict()).isEqualTo(ModelComparison.Verdict.MEJOR);
        assertThat(ModelComparison.compare(better, current).approvable()).isTrue();
        ModelComparison.Result w = ModelComparison.compare(worse, current);
        assertThat(w.verdict()).isEqualTo(ModelComparison.Verdict.PEOR);
        assertThat(w.blockers()).hasSize(3);
        assertThat(ModelComparison.compare(unfair, null).verdict()).isEqualTo(ModelComparison.Verdict.SIN_REFERENCIA);
        assertThat(ModelComparison.compare(unfair, null).approvable()).isFalse();
        assertThat(ModelComparison.compare(MAPPER.readTree("{}"), null).blockers()).isNotEmpty();
        assertThat(ModelComparison.compare(MAPPER.readTree("{\"auc\":0.80}"), current).verdict())
                .isEqualTo(ModelComparison.Verdict.EQUIVALENTE);
    }

    @Test
    void mlModelLifecycle() {
        MlModel m = MlModel.register(new MlModel.Card("lgbm-1", "lightgbm", Instant.now(), "[]", "[]", "[]", "{}",
                "probabilities"), "modelos/lgbm-1.onnx", "abc", UUID.randomUUID(), Instant.now());
        assertThat(m.getStatus()).isEqualTo(MlModel.Status.REGISTRADO);
        m.approve("{}", UUID.randomUUID(), Instant.now());
        assertThat(m.getStatus()).isEqualTo(MlModel.Status.APROBADO);
        assertThatThrownBy(() -> m.approve("{}", UUID.randomUUID(), Instant.now())).isInstanceOf(DomainException.class);
        m.retire();
        assertThat(m.getStatus()).isEqualTo(MlModel.Status.RETIRADO);
    }

    // ------------------------------------------------------------------ guardarraíles y planes

    @Test
    void visibilityFollowsTheMinimumNecessaryPerRole() {
        assertThat(FactorVisibility.categoriesFor(Set.of(Role.DOCENTE)))
                .containsExactlyInAnyOrder(FactorCategory.ACADEMICO, FactorCategory.ASISTENCIA);
        assertThat(FactorVisibility.categoriesFor(Set.of(Role.COORDINADOR)))
                .doesNotContain(FactorCategory.SOCIOECONOMICO, FactorCategory.BIENESTAR);
        assertThat(FactorVisibility.categoriesFor(Set.of(Role.CONSEJERO))).doesNotContain(FactorCategory.BIENESTAR)
                .contains(FactorCategory.SOCIOECONOMICO);
        assertThat(FactorVisibility.categoriesFor(Set.of(Role.ADMIN))).doesNotContain(FactorCategory.BIENESTAR);
        assertThat(FactorVisibility.categoriesFor(Set.of(Role.PSICOLOGO))).contains(FactorCategory.BIENESTAR);
        assertThat(FactorVisibility.categoriesFor(Set.of(Role.ESTUDIANTE))).isEmpty();
        assertThat(FactorVisibility.canSeeAiText(Set.of(Role.DOCENTE), false)).isFalse();
        assertThat(FactorVisibility.canSeeAiText(Set.of(Role.COORDINADOR), false)).isTrue();
        assertThat(FactorVisibility.canSeeAiText(Set.of(Role.CONSEJERO), true)).isFalse();
        assertThat(FactorVisibility.canSeeAiText(Set.of(Role.PSICOLOGO), true)).isTrue();
        assertThat(FactorVisibility.canSeeHealthCauses(Set.of(Role.CONSEJERO))).isFalse();
    }

    @Test
    void planTemplatesAreCopiedFromPrototypesAndOnlyCiteEligibleResources() {
        SupportPlanTemplates.PlanTemplate critical = SupportPlanTemplates.forLevel(AttendanceLevel.CRITICO,
                new SupportPlanTemplates.Context(true, true, true,
                        Set.of("CONSEJERIA-ACADEMICA", "TUT-ACADEMICA", "FLEX-ACADEMICA")));
        assertThat(critical.actions()).extracting(SupportPlanTemplates.PlanAction::type)
                .contains("CONTACTO_DOCENTE", "CONSEJERIA", "TUTORIA", "AJUSTE_HORARIO")
                // Sin elegibilidad para el auxilio de transporte, la acción no se propone.
                .doesNotContain("APOYO_TRANSPORTE");
        assertThat(critical.actions()).filteredOn(a -> a.type().equals("REMISION_BIENESTAR")).singleElement()
                .satisfies(a -> assertThat(a.resourceId()).isNull());
        assertThat(critical.studentMessage()).contains("{{nombre}}", "{{materia}}").doesNotContain("riesgo");
        // El prototipo no cambió: un aviso sin contexto solo trae la conversación con el docente.
        SupportPlanTemplates.PlanTemplate warning = SupportPlanTemplates.forLevel(AttendanceLevel.AVISO,
                new SupportPlanTemplates.Context(false, false, false, Set.of()));
        assertThat(warning.actions()).hasSize(1);
        assertThat(warning.copy().add(warning.actions().get(0)).actions()).hasSize(1);
        assertThat(SupportPlanTemplates.forLevel(AttendanceLevel.PERDIDA,
                new SupportPlanTemplates.Context(false, false, true, Set.of("AUX-TRANSPORTE"))).actions())
                .extracting(SupportPlanTemplates.PlanAction::type).contains("APOYO_TRANSPORTE", "AJUSTE_HORARIO");
    }

    @Test
    void supportPlanNeedsHumanReview() {
        SupportPlan plan = SupportPlan.propose(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AttendanceLevel.CRITICO, SupportPlan.Source.RULES, "[]", "Hola {{nombre}}", null, null, null,
                "BELOW_MIN_LEVEL", Instant.now());
        assertThat(plan.getStatus()).isEqualTo(SupportPlan.Status.PROPUESTO);
        assertThatThrownBy(() -> plan.reject(UUID.randomUUID(), " ", Instant.now())).isInstanceOf(DomainException.class);
        plan.approve(UUID.randomUUID(), "De acuerdo", Instant.now());
        assertThat(plan.getStatus()).isEqualTo(SupportPlan.Status.APROBADO);
        assertThatThrownBy(() -> plan.approve(UUID.randomUUID(), null, Instant.now())).isInstanceOf(DomainException.class);
        SupportPlan other = SupportPlan.propose(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AttendanceLevel.AVISO, SupportPlan.Source.AI, "[]", "m", "claude", "p", "h", null, Instant.now());
        other.supersede(Instant.now());
        assertThat(other.getStatus()).isEqualTo(SupportPlan.Status.REEMPLAZADO);
        assertThatThrownBy(() -> SupportPlan.propose(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AttendanceLevel.NINGUNO, SupportPlan.Source.RULES, "[]", "m", null, null, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void biasMetricsSuppressSmallCells() {
        List<BiasMetrics.Row> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(new BiasMetrics.Row("ISW", 1 + (i % 2), 3, i < 6 ? RiskLevel.ALTO : RiskLevel.BAJO));
        }
        for (int i = 0; i < 12; i++) {
            rows.add(new BiasMetrics.Row("ISW", 3, 3, i < 3 ? RiskLevel.ALTO : RiskLevel.MEDIO));
        }
        for (int i = 0; i < 4; i++) {
            rows.add(new BiasMetrics.Row("MED", 6, 8, RiskLevel.ALTO));
        }
        BiasMetrics.Report report = BiasMetrics.compute(rows, 10);
        assertThat(report.byProgram()).filteredOn(c -> c.group().equals("MED")).singleElement()
                .satisfies(c -> {
                    assertThat(c.suppressed()).isTrue();
                    assertThat(c.students()).isNull();
                    assertThat(c.byLevel()).isEmpty();
                });
        assertThat(report.byStratum()).filteredOn(c -> c.group().equals("1-2")).singleElement()
                .satisfies(c -> assertThat(c.highShare()).isEqualTo(0.5));
        assertThat(report.maxHighShareGapByStratum()).isEqualTo(0.25);
        assertThat(BiasMetrics.stratumBand(null)).isEqualTo("SIN_DATO");
        assertThat(report.bySemester()).extracting(BiasMetrics.Cell::group).contains("3", "8");
    }

    @Test
    void stateTracksLastAssessmentAndWatch() {
        UUID student = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        RiskStudentState state = RiskStudentState.of(student, now);
        assertThat(state.watched(now)).isFalse();
        state.watch(now.plusSeconds(3600), "UMBRAL_CRITICO", now);
        state.watch(now.plusSeconds(60), "OTRO", now);
        assertThat(state.getWatchReason()).isEqualTo("UMBRAL_CRITICO");
        assertThat(state.watched(now)).isTrue();
        Map<String, Object> none = new HashMap<>();
        RiskAssessment a = RiskAssessment.record(new RiskAssessment.Data(student, null, RiskLevel.MEDIO, RiskLevel.MEDIO,
                null, new BigDecimal("40"), ScoreType.RULES, new BigDecimal("0.9"), co.edu.retencion.risk.AssessmentSource.RULES,
                co.edu.retencion.risk.RiskTrigger.MANUAL, "[]", null, null, null, null, null, false, "rules-x", null, null,
                null, "h", 0, 0, 0, "BELOW_MIN_LEVEL", UUID.randomUUID(), 3, 2, null), now);
        assertThat(a.getProbability()).as("un puntaje de reglas nunca guarda probabilidad").isNull();
        state.assessed(a, now);
        assertThat(state.getLastLevel()).isEqualTo(RiskLevel.MEDIO);
        assertThat(state.getLastPayloadHash()).isEqualTo("h");
        assertThat(none).isEmpty();
    }
}

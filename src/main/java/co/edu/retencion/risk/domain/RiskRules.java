package co.edu.retencion.risk.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.RecordsSnapshot.OpenCause;
import co.edu.retencion.records.RecordsSnapshot.SubjectSnapshot;
import co.edu.retencion.risk.domain.Factor.FactorCategory;
import co.edu.retencion.students.SocioeconomicInfo;
import co.edu.retencion.students.TransportMode;

/**
 * Reglas del motor (cada una es una {@link RiskRule}). Los umbrales y puntos viven en
 * {@code app_settings} y se pueden cambiar sin redeploy.
 *
 * <p><b>Decisión de sesgo (documentada y probada):</b> el estrato NUNCA suma puntos. Ninguna regla
 * lo lee; {@link SocioeconomicContextRule} usa trabajo, personas a cargo y transporte solo cuando ya
 * hay una señal académica o de asistencia, como explicación de contexto con un aporte pequeño y
 * acotado, y sobre todo para elegir el apoyo adecuado (auxilio de transporte, flexibilización).
 */
public final class RiskRules {

    static final BigDecimal PASSING = new BigDecimal("3.0");

    private RiskRules() {
    }

    /** Todas las reglas, en orden de presentación. */
    public static List<RiskRule> all() {
        return List.of(new LowAverageRule(), new FailingSubjectsRule(), new AbsencesRule(), new TardiesRule(),
                new GradeTrendRule(), new ConductRule(), new WellbeingRule(), new CausesRule(),
                new SocioeconomicContextRule());
    }

    /** Promedio del semestre bajo. */
    static final class LowAverageRule implements RiskRule {

        @Override
        public String code() {
            return "LOW_AVERAGE";
        }

        @Override
        public String settingKey() {
            return "risk.rules.low-average";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            BigDecimal avg = f.records().semesterAverage();
            if (avg == null) {
                return List.of();
            }
            BigDecimal severe = s.decimal("severeThreshold", "2.5");
            BigDecimal threshold = s.decimal("threshold", "3.0");
            int points;
            BigDecimal limit;
            if (avg.compareTo(severe) < 0) {
                points = s.integer("severePoints", 25);
                limit = severe;
            } else if (avg.compareTo(threshold) < 0) {
                points = s.integer("points", 15);
                limit = threshold;
            } else {
                return List.of();
            }
            return List.of(new Factor(code(), FactorCategory.ACADEMICO, BigDecimal.valueOf(points),
                    "Promedio del semestre " + one(avg) + " (por debajo de " + one(limit) + ")",
                    Map.of("average", avg, "threshold", limit)));
        }
    }

    /** Materias con promedio menor a 3.0. */
    static final class FailingSubjectsRule implements RiskRule {

        @Override
        public String code() {
            return "FAILING_SUBJECTS";
        }

        @Override
        public String settingKey() {
            return "risk.rules.failing-subjects";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            List<SubjectSnapshot> failing = f.records().subjects().stream().filter(SubjectSnapshot::belowPassing).toList();
            if (failing.isEmpty()) {
                return List.of();
            }
            int points = Math.min(s.integer("maxPoints", 30), failing.size() * s.integer("pointsPerSubject", 10));
            String list = failing.stream().map(x -> name(x) + " (" + one(x.average()) + ")")
                    .collect(Collectors.joining(", "));
            return List.of(new Factor(code(), FactorCategory.ACADEMICO, BigDecimal.valueOf(points),
                    failing.size() + (failing.size() == 1 ? " materia" : " materias") + " con promedio menor a 3.0: " + list,
                    Map.of("subjects", failing.stream().map(x -> Map.of("subject", name(x), "average", x.average()))
                            .toList())));
        }
    }

    /** Faltas que cuentan según el nivel alcanzado en la política de cada materia. */
    static final class AbsencesRule implements RiskRule {

        @Override
        public String code() {
            return "ABSENCES";
        }

        @Override
        public String settingKey() {
            return "risk.rules.absences";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            List<Factor> factors = new ArrayList<>();
            int budget = s.integer("maxPoints", 40);
            for (SubjectSnapshot x : f.records().subjects()) {
                AttendanceLevel level = x.attendanceLevel();
                int points = switch (level == null ? AttendanceLevel.NINGUNO : level) {
                    case AVISO -> s.integer("warningPoints", 8);
                    case CRITICO -> s.integer("criticalPoints", 15);
                    case PERDIDA -> s.integer("lostPoints", 25);
                    case NINGUNO -> 0;
                };
                points = Math.min(points, budget);
                if (points <= 0) {
                    continue;
                }
                budget -= points;
                String text = name(x) + ": " + x.countedAbsences() + " de " + x.maxAbsences() + " faltas ("
                        + switch (level) {
                            case AVISO -> "aviso";
                            case CRITICO -> "nivel crítico";
                            default -> "pérdida por inasistencia";
                        } + ")";
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("subject", name(x));
                details.put("groupId", x.groupId());
                details.put("absences", x.countedAbsences());
                details.put("maxAbsences", x.maxAbsences());
                details.put("attendanceLevel", level.name());
                factors.add(new Factor(code(), FactorCategory.ASISTENCIA, BigDecimal.valueOf(points), text, details));
            }
            return factors;
        }
    }

    /** Tardanzas acumuladas en el periodo. */
    static final class TardiesRule implements RiskRule {

        @Override
        public String code() {
            return "TARDIES";
        }

        @Override
        public String settingKey() {
            return "risk.rules.tardies";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            int tardies = f.records().subjects().stream().mapToInt(SubjectSnapshot::tardies).sum();
            if (tardies < Math.max(1, s.integer("threshold", 3))) {
                return List.of();
            }
            return List.of(new Factor(code(), FactorCategory.ASISTENCIA, BigDecimal.valueOf(s.integer("points", 5)),
                    tardies + " tardanzas en el periodo", Map.of("tardies", tardies)));
        }
    }

    /** Materias con notas a la baja. */
    static final class GradeTrendRule implements RiskRule {

        @Override
        public String code() {
            return "GRADE_TREND";
        }

        @Override
        public String settingKey() {
            return "risk.rules.grade-trend";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            List<SubjectSnapshot> down = f.records().subjects().stream().filter(x -> "BAJA".equals(x.trend())).toList();
            if (down.isEmpty()) {
                return List.of();
            }
            int points = Math.min(s.integer("maxPoints", 15), down.size() * s.integer("pointsPerSubject", 5));
            return List.of(new Factor(code(), FactorCategory.ACADEMICO, BigDecimal.valueOf(points),
                    "Notas a la baja en " + down.stream().map(RiskRules::name).collect(Collectors.joining(", ")),
                    Map.of("subjects", down.stream().map(RiskRules::name).toList())));
        }
    }

    /** Observaciones de conducta. */
    static final class ConductRule implements RiskRule {

        @Override
        public String code() {
            return "CONDUCT";
        }

        @Override
        public String settingKey() {
            return "risk.rules.conduct";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            int count = f.records().conductObservations();
            if (count < Math.max(1, s.integer("threshold", 1))) {
                return List.of();
            }
            int points = Math.min(s.integer("maxPoints", 12), count * s.integer("pointsPerObservation", 4));
            return List.of(new Factor(code(), FactorCategory.CONDUCTA, BigDecimal.valueOf(points),
                    count + (count == 1 ? " observación" : " observaciones") + " de conducta en el periodo",
                    Map.of("observations", count)));
        }
    }

    /** Remisiones de bienestar abiertas (solo el conteo; dato sensible). */
    static final class WellbeingRule implements RiskRule {

        @Override
        public String code() {
            return "WELLBEING";
        }

        @Override
        public String settingKey() {
            return "risk.rules.wellbeing";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            if (f.wellbeing() == null || f.wellbeing().openReferrals() <= 0) {
                return List.of();
            }
            int points = (int) Math.min(s.integer("maxPoints", 10),
                    f.wellbeing().openReferrals() * s.integer("openReferralPoints", 8));
            return List.of(new Factor(code(), FactorCategory.BIENESTAR, BigDecimal.valueOf(points),
                    "Tiene remisiones de bienestar abiertas", Map.of("openReferrals", f.wellbeing().openReferrals())));
        }
    }

    /** Causas abiertas por categoría; las de salud forman un factor aparte de categoría BIENESTAR. */
    static final class CausesRule implements RiskRule {

        @Override
        public String code() {
            return "OPEN_CAUSES";
        }

        @Override
        public String settingKey() {
            return "risk.rules.causes";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            List<OpenCause> causes = f.records().openCauses();
            if (causes.isEmpty()) {
                return List.of();
            }
            int budget = s.integer("maxPoints", 20);
            List<Factor> factors = new ArrayList<>();
            List<OpenCause> health = causes.stream().filter(c -> "SALUD".equals(c.category())).toList();
            List<OpenCause> others = causes.stream().filter(c -> !"SALUD".equals(c.category())).toList();
            if (!others.isEmpty()) {
                int points = Math.min(budget, others.stream()
                        .mapToInt(c -> s.pointsFor("points", c.category(), 3)).sum());
                budget -= points;
                factors.add(new Factor(code(), FactorCategory.CAUSAS, BigDecimal.valueOf(points),
                        others.size() + (others.size() == 1 ? " causa abierta" : " causas abiertas") + ": "
                                + others.stream().map(OpenCause::code).collect(Collectors.joining(", ")),
                        Map.of("causes", others.stream().map(OpenCause::code).toList())));
            }
            if (!health.isEmpty() && budget > 0) {
                int points = Math.min(budget, health.size() * s.pointsFor("points", "SALUD", 6));
                factors.add(new Factor("HEALTH_CAUSES", FactorCategory.BIENESTAR, BigDecimal.valueOf(points),
                        "Causas de salud registradas por bienestar", Map.of("count", health.size())));
            }
            return factors;
        }
    }

    /**
     * Contexto socioeconómico SOLO combinado con una señal académica o de asistencia. No lee el
     * estrato (ver la clase).
     */
    static final class SocioeconomicContextRule implements RiskRule {

        @Override
        public String code() {
            return "SOCIOECONOMIC_CONTEXT";
        }

        @Override
        public String settingKey() {
            return "risk.rules.socioeconomic-context";
        }

        @Override
        public List<Factor> evaluate(RiskFeatures f, RuleSettings s) {
            SocioeconomicInfo p = f.socioeconomic();
            if (p == null || !f.hasAcademicOrAttendanceSignal()) {
                return List.of();
            }
            boolean absences = f.records().subjects().stream()
                    .anyMatch(x -> x.attendanceLevel() != null && x.attendanceLevel() != AttendanceLevel.NINGUNO);
            List<String> reasons = new ArrayList<>();
            if (Boolean.TRUE.equals(p.works())) {
                reasons.add("trabaja (posible cruce de horarios)");
            }
            if (absences && p.transport() == TransportMode.TRANSPORTE_PUBLICO) {
                reasons.add("depende del transporte público y tiene faltas");
            }
            if (p.dependents() != null && p.dependents() >= 3) {
                reasons.add("tiene 3 o más personas a cargo");
            }
            if (reasons.isEmpty()) {
                return List.of();
            }
            int points = Math.min(s.integer("maxPoints", 8), reasons.size() * s.integer("combinedPoints", 4));
            return List.of(new Factor(code(), FactorCategory.SOCIOECONOMICO, BigDecimal.valueOf(points),
                    "Contexto que puede explicar las dificultades: " + String.join("; ", reasons),
                    Map.of("reasons", reasons)));
        }
    }

    static String name(SubjectSnapshot s) {
        return s.subjectName() == null ? "Materia" : s.subjectName();
    }

    static String one(BigDecimal value) {
        return value == null ? "-" : value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}

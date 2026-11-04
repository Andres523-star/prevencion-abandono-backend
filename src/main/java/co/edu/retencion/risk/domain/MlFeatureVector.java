package co.edu.retencion.risk.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import co.edu.retencion.records.AttendanceLevel;
import co.edu.retencion.records.RecordsSnapshot;
import co.edu.retencion.records.RecordsSnapshot.SubjectSnapshot;

/**
 * Contrato de variables del modelo predictivo propio (dataset, entrenamiento y servicio usan esta
 * misma definición).
 *
 * <p><b>Revisión de sesgo:</b> el modelo NO recibe estrato ni variables socioeconómicas (trabajo,
 * personas a cargo, transporte, causas económicas) ni datos de salud o bienestar. Un modelo cuya
 * ficha declare alguna variable prohibida o desconocida se rechaza al registrarlo. Las columnas
 * {@code audit_*} del dataset sirven solo para medir el desempeño por grupo y el script de
 * entrenamiento las excluye.
 */
public final class MlFeatureVector {

    /** Variables permitidas, en el orden canónico del dataset. */
    public static final List<String> FEATURES = List.of(
            "semester", "semester_average", "subjects_below_passing", "subject_count", "max_absence_ratio",
            "total_counted_absences", "subjects_at_critical_or_lost", "total_tardies", "subjects_trend_down",
            "conduct_observations", "academic_observations", "open_academic_causes", "open_personal_causes",
            "open_institutional_causes");

    /** Palabras (separadas por "_") que nunca pueden formar parte del nombre de una variable del modelo. */
    static final Set<String> FORBIDDEN_TOKENS = Set.of("stratum", "estrato", "works", "work", "trabaja", "dependents",
            "transport", "income", "ingreso", "socio", "socioeconomic", "economic", "economica", "wellbeing",
            "bienestar", "health", "salud", "gender", "genero", "sexo", "age", "edad", "audit");

    private MlFeatureVector() {
    }

    /** Valores por nombre, en el orden canónico. El promedio ausente se codifica como -1. */
    public static Map<String, Double> values(int semester, RecordsSnapshot records) {
        Map<String, Double> v = new LinkedHashMap<>();
        List<SubjectSnapshot> subjects = records.subjects();
        BigDecimal avg = records.semesterAverage();
        double maxRatio = subjects.stream().filter(s -> s.maxAbsences() > 0)
                .mapToDouble(s -> (double) s.countedAbsences() / s.maxAbsences()).max().orElse(0);
        v.put("semester", (double) semester);
        v.put("semester_average", avg == null ? -1.0 : avg.doubleValue());
        v.put("subjects_below_passing", (double) records.subjectsBelowPassing());
        v.put("subject_count", (double) subjects.size());
        v.put("max_absence_ratio", Math.min(1.5, maxRatio));
        v.put("total_counted_absences", (double) subjects.stream().mapToInt(SubjectSnapshot::countedAbsences).sum());
        v.put("subjects_at_critical_or_lost", (double) subjects.stream()
                .filter(s -> s.attendanceLevel() == AttendanceLevel.CRITICO || s.attendanceLevel() == AttendanceLevel.PERDIDA)
                .count());
        v.put("total_tardies", (double) subjects.stream().mapToInt(SubjectSnapshot::tardies).sum());
        v.put("subjects_trend_down", (double) subjects.stream().filter(s -> "BAJA".equals(s.trend())).count());
        v.put("conduct_observations", (double) records.conductObservations());
        v.put("academic_observations", (double) records.academicObservations());
        v.put("open_academic_causes", count(records, "ACADEMICA"));
        v.put("open_personal_causes", count(records, "PERSONAL"));
        v.put("open_institutional_causes", count(records, "INSTITUCIONAL"));
        return v;
    }

    /** Vector en el orden que declara el modelo. */
    public static float[] vector(List<String> modelFeatures, Map<String, Double> values) {
        float[] out = new float[modelFeatures.size()];
        for (int i = 0; i < out.length; i++) {
            Double value = values.get(modelFeatures.get(i));
            if (value == null) {
                throw new IllegalArgumentException("Variable sin valor: " + modelFeatures.get(i));
            }
            out[i] = value.floatValue();
        }
        return out;
    }

    /** Errores de la lista de variables de un modelo (vacío = aceptable). */
    public static List<String> validate(List<String> modelFeatures) {
        List<String> errors = new ArrayList<>();
        if (modelFeatures == null || modelFeatures.isEmpty()) {
            errors.add("El modelo no declara variables");
            return errors;
        }
        for (String f : modelFeatures) {
            List<String> tokens = List.of(f.toLowerCase(Locale.ROOT).split("[_.\\s-]+"));
            if (tokens.stream().anyMatch(FORBIDDEN_TOKENS::contains)) {
                errors.add("Variable prohibida por la política de sesgo: " + f);
            } else if (!FEATURES.contains(f)) {
                errors.add("Variable desconocida: " + f);
            }
        }
        if (modelFeatures.stream().distinct().count() != modelFeatures.size()) {
            errors.add("Variables repetidas");
        }
        return errors;
    }

    private static double count(RecordsSnapshot records, String category) {
        return records.openCauses().stream().filter(c -> category.equals(c.category())).count();
    }
}

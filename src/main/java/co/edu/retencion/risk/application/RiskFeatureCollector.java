package co.edu.retencion.risk.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.ai.CaseFacts;
import co.edu.retencion.ai.ResourceCatalog.EligibilityProfile;
import co.edu.retencion.records.RecordsSnapshot;
import co.edu.retencion.records.RecordsSnapshot.SubjectSnapshot;
import co.edu.retencion.records.StudentRecords;
import co.edu.retencion.risk.domain.RiskFeatures;
import co.edu.retencion.risk.domain.RiskScorer.ScoreResult;
import co.edu.retencion.students.SocioeconomicInfo;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.wellbeing.WellbeingSignals;

/**
 * Reúne las variables de un estudiante desde las API públicas de los demás módulos (nunca desde sus
 * tablas) y las traduce a los hechos seudonimizados que consume el módulo de IA.
 */
@Component
public class RiskFeatureCollector {

    private final StudentRegistry students;
    private final StudentRecords records;
    private final WellbeingSignals wellbeing;
    private final AcademicDirectory academic;

    public RiskFeatureCollector(StudentRegistry students, StudentRecords records, WellbeingSignals wellbeing,
                                AcademicDirectory academic) {
        this.students = students;
        this.records = records;
        this.wellbeing = wellbeing;
        this.academic = academic;
    }

    public Optional<RiskFeatures> collect(UUID studentId) {
        return collect(studentId, null);
    }

    /** @param termId periodo de la foto ({@code null} = actual) */
    public Optional<RiskFeatures> collect(UUID studentId, UUID termId) {
        Optional<StudentInfo> student = students.findById(studentId);
        if (student.isEmpty()) {
            return Optional.empty();
        }
        StudentInfo s = student.get();
        String programCode = academic.findProgram(s.programId()).map(ProgramInfo::code).orElse("NA");
        RecordsSnapshot snapshot = records.snapshot(studentId, termId);
        SocioeconomicInfo socio = students.socioeconomic(studentId).orElse(null);
        return Optional.of(new RiskFeatures(studentId, s.anonCode(), s.programId(), programCode, s.currentSemester(),
                snapshot, socio, wellbeing.summary(studentId)));
    }

    /** Datos de elegibilidad de recursos (el estrato se usa aquí, en el backend, y nunca sale a la IA). */
    public EligibilityProfile eligibility(RiskFeatures f) {
        SocioeconomicInfo p = f.socioeconomic();
        return new EligibilityProfile(f.programCode(), f.semester(), p == null ? null : p.stratum(),
                p == null ? null : p.works(), p == null || p.transport() == null ? null : p.transport().name());
    }

    /** Referencia corta de cada materia en el payload (M1, M2...), en el orden de la foto. */
    public static String subjectRef(int index) {
        return "M" + (index + 1);
    }

    /** Hechos para la IA: sin estrato y con el texto libre solo si se pide (el anonimizador decide). */
    public CaseFacts caseFacts(RiskFeatures f, ScoreResult score, List<String> freeTexts) {
        List<CaseFacts.SubjectFacts> subjects = new ArrayList<>();
        List<SubjectSnapshot> list = f.records().subjects();
        for (int i = 0; i < list.size(); i++) {
            SubjectSnapshot x = list.get(i);
            subjects.add(new CaseFacts.SubjectFacts(subjectRef(i), x.subjectName(), x.average(), x.gradeCount(),
                    x.trend(), x.countedAbsences(), x.maxAbsences(),
                    x.attendanceLevel() == null ? "NINGUNO" : x.attendanceLevel().name(), x.tardies(), x.pending()));
        }
        SocioeconomicInfo p = f.socioeconomic();
        CaseFacts.ContextFacts context = p == null ? null : new CaseFacts.ContextFacts(p.works(), p.dependents(),
                p.transport() == null ? null : p.transport().name());
        CaseFacts.ScoreFacts scoreFacts = score == null ? null : new CaseFacts.ScoreFacts(score.score(),
                score.scoreType().name(), score.level().name(), score.factors().stream()
                .map(x -> new CaseFacts.FactorFacts(x.code(), x.category().name(), x.points(), x.explanation()))
                .toList());
        return new CaseFacts(f.anonCode(), f.programCode(), f.semester(), f.records().semesterAverage(),
                f.records().subjectsBelowPassing(), subjects, f.records().academicObservations(),
                f.records().conductObservations(),
                f.records().openCauses().stream().map(c -> new CaseFacts.CauseFacts(c.code(), c.category())).toList(),
                context, f.wellbeing() == null ? null
                : new CaseFacts.WellbeingFacts(f.wellbeing().openReferrals(),
                        f.wellbeing().psychologicalAssessments() + f.wellbeing().physicalAssessments()),
                scoreFacts, freeTexts);
    }

    /** Textos de observaciones (solo se leen si la configuración permite texto libre hacia la IA). */
    public List<String> freeTexts(UUID studentId, boolean enabled) {
        return enabled ? records.recentObservationTexts(studentId, 5) : List.of();
    }
}

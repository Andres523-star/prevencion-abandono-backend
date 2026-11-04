package co.edu.retencion.risk.application;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.TermInfo;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.risk.domain.DatasetExport;
import co.edu.retencion.risk.domain.DatasetExportRepository;
import co.edu.retencion.risk.domain.MlFeatureVector;
import co.edu.retencion.risk.domain.RiskFeatures;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.students.StudentStatus;

/**
 * Exporta el dataset seudonimizado para entrenar el modelo fuera de línea: una fila por estudiante y
 * periodo CERRADO con sus variables ({@link MlFeatureVector#FEATURES}) y la etiqueta de deserción.
 *
 * <p>Etiqueta: 1 si el estudiante no aparece inscrito en el periodo siguiente (y no está GRADUADO);
 * 0 si sigue. Las filas de graduados se omiten. Identificador de fila: HMAC con una clave aleatoria
 * de un solo uso (no es el anon_code ni se puede enlazar con otra exportación). Columnas
 * {@code audit_*} (programa, banda de estrato) solo para medir el desempeño por grupo: el script de
 * entrenamiento las excluye de las variables.
 */
@Service
public class DatasetExportService {

    private final AcademicDirectory academic;
    private final StudentRegistry students;
    private final RiskFeatureCollector collector;
    private final DatasetExportRepository exports;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DatasetExportService(AcademicDirectory academic, StudentRegistry students, RiskFeatureCollector collector,
                                DatasetExportRepository exports, ObjectMapper mapper, TransactionTemplate tx,
                                Clock clock) {
        this.academic = academic;
        this.students = students;
        this.collector = collector;
        this.exports = exports;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param id      registro de la exportación
     * @param csv     contenido CSV (UTF-8)
     * @param rows    filas de datos
     * @param sha256  hash del contenido
     */
    public record Export(UUID id, byte[] csv, int rows, String sha256) {
    }

    /**
     * @param termCodes periodos a incluir ({@code null} o vacío = todos los cerrados con periodo siguiente)
     * @param reason    motivo (obligatorio; queda en la auditoría y en el registro)
     */
    public Export export(List<String> termCodes, String reason, UUID requestedBy) {
        if (reason == null || reason.isBlank()) {
            throw DomainException.badRequest("La exportación exige un motivo");
        }
        List<TermInfo> terms = academic.listTerms();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        StringBuilder csv = new StringBuilder();
        csv.append("row_id,term_code,label_term_code,").append(String.join(",", MlFeatureVector.FEATURES))
                .append(",audit_program,audit_stratum_band,label_dropout\n");
        int rows = 0;
        List<String> included = new ArrayList<>();
        for (int i = 0; i + 1 < terms.size(); i++) {
            TermInfo term = terms.get(i);
            TermInfo next = terms.get(i + 1);
            boolean selected = termCodes == null || termCodes.isEmpty() ? "CLOSED".equals(term.status())
                    : termCodes.contains(term.code());
            if (!selected) {
                continue;
            }
            included.add(term.code());
            Set<UUID> enrolledNext = students.studentIdsEnrolledInTerm(next.id());
            for (UUID studentId : students.studentIdsEnrolledInTerm(term.id())) {
                Optional<StudentInfo> student = students.findById(studentId);
                if (student.isEmpty() || student.get().status() == StudentStatus.GRADUADO) {
                    continue;
                }
                Optional<RiskFeatures> features = collector.collect(studentId, term.id());
                if (features.isEmpty()) {
                    continue;
                }
                int semester = semesterAt(student.get(), term, terms);
                Map<String, Double> values = MlFeatureVector.values(semester, features.get().records());
                Integer stratum = features.get().socioeconomic() == null ? null : features.get().socioeconomic().stratum();
                csv.append(pseudonym(key, studentId)).append(',').append(term.code()).append(',').append(next.code());
                for (String f : MlFeatureVector.FEATURES) {
                    csv.append(',').append(format(values.get(f)));
                }
                csv.append(',').append(features.get().programCode()).append(',').append(band(stratum)).append(',')
                        .append(enrolledNext.contains(studentId) ? 0 : 1).append('\n');
                rows++;
            }
        }
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        String sha = RiskJson.sha256(content);
        int total = rows;
        DatasetExport saved = tx.execute(s -> exports.save(DatasetExport.of(requestedBy, reason.trim(),
                json(included), total, sha, clock.instant())));
        return new Export(saved.getId(), content, rows, sha);
    }

    /** Semestre aproximado en el periodo: semestre actual menos los periodos posteriores cursados. */
    private int semesterAt(StudentInfo student, TermInfo term, List<TermInfo> terms) {
        Set<UUID> later = terms.stream().filter(t -> t.startDate().isAfter(term.startDate()))
                .map(TermInfo::id).collect(Collectors.toSet());
        long laterEnrolled = students.enrollmentsOf(student.id()).stream().map(EnrollmentInfo::termId)
                .filter(later::contains).distinct().count();
        return (int) Math.max(1, student.currentSemester() - laterEnrolled);
    }

    private static String band(Integer stratum) {
        if (stratum == null) {
            return "SIN_DATO";
        }
        return stratum <= 2 ? "1-2" : stratum <= 4 ? "3-4" : "5-6";
    }

    private static String format(Double value) {
        if (value == null) {
            return "";
        }
        return value == Math.rint(value) ? String.valueOf(value.longValue()) : String.valueOf(value);
    }

    private static String pseudonym(byte[] key, UUID studentId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(studentId.toString().getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 20);
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

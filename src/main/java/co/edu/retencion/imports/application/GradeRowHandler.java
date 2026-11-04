package co.edu.retencion.imports.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupInfo;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.academic.TermInfo;
import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.RowOutcome;
import co.edu.retencion.imports.domain.RowValues;
import co.edu.retencion.records.GradeBook;
import co.edu.retencion.students.EnrollmentInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Notas desde el sistema académico. Clave natural: inscripción (documento + programa + materia +
 * periodo + grupo) + fecha + descripción: reimportar el mismo archivo no duplica notas y una nota
 * corregida en el origen se actualiza.
 */
@Component
class GradeRowHandler implements RowHandler {

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.required("documento", "1020304050", "numero_documento", "cedula", "identificacion"),
            FieldSpec.required("programa", "MED", "codigo_programa"),
            FieldSpec.required("materia", "MED-201", "codigo_materia"),
            FieldSpec.required("periodo", "2026-2", "codigo_periodo"),
            FieldSpec.required("grupo", "A", "codigo_grupo"),
            FieldSpec.required("nota", "3.8", "calificacion", "valor"),
            FieldSpec.required("fecha", "2026-09-15", "fecha_evaluacion"),
            FieldSpec.optional("descripcion", "Parcial 1", "evaluacion", "actividad"));

    private final AcademicDirectory directory;
    private final StudentRegistry students;
    private final GradeBook grades;

    GradeRowHandler(AcademicDirectory directory, StudentRegistry students, GradeBook grades) {
        this.directory = directory;
        this.students = students;
        this.grades = grades;
    }

    @Override
    public ImportJob.Type type() {
        return ImportJob.Type.GRADES;
    }

    @Override
    public List<FieldSpec> fields() {
        return FIELDS;
    }

    @Override
    public RowOutcome process(RowValues row, Context context) {
        String document = row.required("documento");
        String programCode = row.required("programa");
        String subjectCode = row.required("materia");
        String termCode = row.required("periodo");
        String groupCode = row.required("grupo");
        BigDecimal value = parseGrade(row);
        LocalDate date = row.requiredDate("fecha");
        String description = row.optional("descripcion");
        if (description != null && description.length() > 200) {
            row.addError("descripcion: máximo 200 caracteres");
        }
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        Optional<UUID> student = students.findIdByDocument(document);
        Optional<ProgramInfo> program = directory.findProgramByCode(programCode);
        Optional<TermInfo> term = directory.findTermByCode(termCode);
        Optional<GroupInfo> group = program.flatMap(p -> directory.findSubjectId(p.id(), subjectCode))
                .flatMap(subjectId -> term.flatMap(t -> directory.findGroup(subjectId, t.id(), groupCode)));
        Optional<EnrollmentInfo> enrollment = student.flatMap(s -> group.flatMap(g -> students.findEnrollment(s, g.id())));
        if (enrollment.isEmpty()) {
            row.addError("inscripcion: el estudiante no está inscrito en ese grupo (verifique documento, programa, materia, periodo y grupo)");
        }
        context.firstOccurrence(String.join("|", "grade", document, programCode, subjectCode, termCode, groupCode,
                date == null ? "" : date.toString(), description == null ? "" : description).toUpperCase(Locale.ROOT), row);
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        if (context.dryRun()) {
            return RowOutcome.of(RowOutcome.Result.VALID);
        }
        return RowOutcome.of(RowOutcome.Result.valueOf(
                grades.upsert(enrollment.get().id(), value, date, description, context.actor()).name()));
    }

    /** Acepta punto o coma decimal; escala 0.0–5.0. */
    private static BigDecimal parseGrade(RowValues row) {
        String raw = row.required("nota");
        if (raw == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim().replace(',', '.'));
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.valueOf(5)) > 0) {
                row.addError("nota: debe estar entre 0.0 y 5.0");
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            row.addError("nota: no es un número");
            return null;
        }
    }
}

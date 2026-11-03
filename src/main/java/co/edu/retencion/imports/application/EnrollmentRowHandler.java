package co.edu.retencion.imports.application;

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
import co.edu.retencion.students.StudentRegistry;

/** Inscripciones. Clave natural: documento + programa + materia + periodo + grupo. */
@Component
class EnrollmentRowHandler implements RowHandler {

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.required("documento", "1020304050", "numero_documento", "cedula", "identificacion"),
            FieldSpec.required("programa", "MED", "codigo_programa"),
            FieldSpec.required("materia", "MED-201", "codigo_materia"),
            FieldSpec.required("periodo", "2026-2", "codigo_periodo"),
            FieldSpec.required("grupo", "A", "codigo_grupo"));

    private final AcademicDirectory directory;
    private final StudentRegistry students;

    EnrollmentRowHandler(AcademicDirectory directory, StudentRegistry students) {
        this.directory = directory;
        this.students = students;
    }

    @Override
    public ImportJob.Type type() {
        return ImportJob.Type.ENROLLMENTS;
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
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        Optional<UUID> student = students.findIdByDocument(document);
        if (student.isEmpty()) {
            row.addError("documento: no hay un estudiante con ese documento");
        }
        Optional<ProgramInfo> program = directory.findProgramByCode(programCode);
        Optional<TermInfo> term = directory.findTermByCode(termCode);
        Optional<GroupInfo> group = program.flatMap(p -> directory.findSubjectId(p.id(), subjectCode))
                .flatMap(subjectId -> term.flatMap(t -> directory.findGroup(subjectId, t.id(), groupCode)));
        if (group.isEmpty()) {
            row.addError("grupo: no existe (verifique programa, materia, periodo y grupo)");
        }
        context.firstOccurrence(String.join("|", "enrollment", document, programCode, subjectCode, termCode, groupCode)
                .toUpperCase(Locale.ROOT), row);
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        if (context.dryRun()) {
            return RowOutcome.of(RowOutcome.Result.VALID);
        }
        return RowOutcome.of(RowOutcome.Result.valueOf(students.enroll(student.get(), group.get().id()).name()));
    }
}

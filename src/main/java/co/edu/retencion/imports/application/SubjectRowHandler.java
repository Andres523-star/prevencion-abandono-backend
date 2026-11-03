package co.edu.retencion.imports.application;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicCatalog;
import co.edu.retencion.academic.AcademicCatalog.SubjectCommand;
import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.RowOutcome;
import co.edu.retencion.imports.domain.RowValues;

/**
 * Materias. Clave natural: programa + código. Los prerrequisitos (códigos separados por ";")
 * deben existir o aparecer en una fila anterior del mismo archivo.
 */
@Component
class SubjectRowHandler implements RowHandler {

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.required("programa", "MED", "codigo_programa"),
            FieldSpec.required("codigo", "MED-201", "codigo_materia"),
            FieldSpec.required("nombre", "Anatomía II", "nombre_materia", "materia"),
            FieldSpec.required("creditos", "4"),
            FieldSpec.required("semestre_sugerido", "2", "semestre"),
            FieldSpec.optional("prerrequisitos", "MED-101;MED-102", "prerequisitos"));

    private final AcademicDirectory directory;
    private final AcademicCatalog catalog;

    SubjectRowHandler(AcademicDirectory directory, AcademicCatalog catalog) {
        this.directory = directory;
        this.catalog = catalog;
    }

    @Override
    public ImportJob.Type type() {
        return ImportJob.Type.SUBJECTS;
    }

    @Override
    public List<FieldSpec> fields() {
        return FIELDS;
    }

    @Override
    public RowOutcome process(RowValues row, Context context) {
        String programCode = row.required("programa");
        Optional<ProgramInfo> program = programCode == null ? Optional.empty() : directory.findProgramByCode(programCode);
        if (programCode != null && program.isEmpty()) {
            row.addError("programa: no existe");
        }
        String code = row.required("codigo");
        String name = row.required("nombre");
        Integer credits = row.requiredInt("creditos", 0, 30);
        Integer semester = row.requiredInt("semestre_sugerido", 1, 14);
        List<String> prerequisites = Optional.ofNullable(row.optional("prerrequisitos"))
                .map(v -> Arrays.stream(v.split("[;|]")).map(String::trim).filter(s -> !s.isEmpty()).toList())
                .orElse(List.of());
        if (program.isPresent() && code != null) {
            for (String prerequisite : prerequisites) {
                boolean known = context.seenKeys().containsKey(key(programCode, prerequisite))
                        || directory.findSubjectId(program.get().id(), prerequisite).isPresent();
                if (!known) {
                    row.addError("prerrequisitos: la materia " + prerequisite + " no existe");
                }
            }
            context.firstOccurrence(key(programCode, code), row);
        }
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        if (context.dryRun()) {
            return RowOutcome.of(RowOutcome.Result.VALID);
        }
        AcademicCatalog.Upsert result = catalog.upsertSubject(
                new SubjectCommand(program.get().id(), code, name, credits, semester, prerequisites));
        return RowOutcome.of(RowOutcome.Result.valueOf(result.outcome().name()));
    }

    private static String key(String program, String code) {
        return "subject:" + program.toUpperCase(Locale.ROOT) + "|" + code.toUpperCase(Locale.ROOT);
    }
}

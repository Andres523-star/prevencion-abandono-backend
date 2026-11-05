package co.edu.retencion.imports.application;

import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.analytics.DropoutHistoryBook;
import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.RowOutcome;
import co.edu.retencion.imports.domain.RowValues;

/**
 * Series históricas de deserción de periodos anteriores a la plataforma, ya agregadas (sin estudiantes).
 * Clave natural: programa + año + mes + semestre: reimportar no duplica y una cifra corregida se
 * actualiza.
 */
@Component
class DropoutHistoryRowHandler implements RowHandler {

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.required("programa", "MED", "codigo_programa"),
            FieldSpec.required("anio", "2023", "año", "year"),
            FieldSpec.optional("mes", "2", "month"),
            FieldSpec.optional("semestre", "3"),
            FieldSpec.required("matriculados", "420", "inscritos", "enrolled"),
            FieldSpec.required("desertores", "37", "retirados", "dropouts"),
            FieldSpec.optional("periodo", "2023-1", "etiqueta"));

    private final AcademicDirectory directory;
    private final DropoutHistoryBook history;

    DropoutHistoryRowHandler(AcademicDirectory directory, DropoutHistoryBook history) {
        this.directory = directory;
        this.history = history;
    }

    @Override
    public ImportJob.Type type() {
        return ImportJob.Type.DROPOUT_HISTORY;
    }

    @Override
    public List<FieldSpec> fields() {
        return FIELDS;
    }

    @Override
    public RowOutcome process(RowValues row, Context context) {
        String programCode = row.required("programa");
        Integer year = row.requiredInt("anio", 1950, 2100);
        Integer month = row.optionalInt("mes", 1, 12);
        Integer semester = row.optionalInt("semestre", 1, 14);
        Integer enrolled = row.requiredInt("matriculados", 0, 1_000_000);
        Integer dropouts = row.requiredInt("desertores", 0, 1_000_000);
        String label = row.optional("periodo");
        if (label != null && label.length() > 20) {
            row.addError("periodo: máximo 20 caracteres");
        }
        if (enrolled != null && dropouts != null && dropouts > enrolled) {
            row.addError("desertores: no puede superar a matriculados");
        }
        Optional<ProgramInfo> program = programCode == null ? Optional.empty() : directory.findProgramByCode(programCode);
        if (programCode != null && program.isEmpty()) {
            row.addError("programa: no existe");
        }
        int effectiveMonth = month == null ? 1 : month;
        context.firstOccurrence(String.join("|", "history", String.valueOf(programCode), String.valueOf(year),
                String.valueOf(effectiveMonth), String.valueOf(semester)).toUpperCase(Locale.ROOT), row);
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        if (context.dryRun()) {
            return RowOutcome.of(RowOutcome.Result.VALID);
        }
        return RowOutcome.of(RowOutcome.Result.valueOf(history.upsert(program.get().id(), YearMonth.of(year, effectiveMonth),
                semester, enrolled, dropouts, label, context.actor()).name()));
    }
}

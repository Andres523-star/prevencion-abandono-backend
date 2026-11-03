package co.edu.retencion.imports.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.RowOutcome;
import co.edu.retencion.imports.domain.RowValues;
import co.edu.retencion.students.DocumentType;
import co.edu.retencion.students.StudentRegistry;
import co.edu.retencion.students.StudentRegistry.StudentRecord;
import co.edu.retencion.students.StudentStatus;
import co.edu.retencion.students.TransportMode;

/** Estudiantes. Clave natural: número de documento. */
@Component
class StudentRowHandler implements RowHandler {

    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern DOCUMENT = Pattern.compile("^[A-Za-z0-9.\\- ]{4,20}$");

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.required("tipo_documento", "CC", "tipo_doc", "tipo_identificacion"),
            FieldSpec.required("documento", "1020304050", "numero_documento", "num_documento", "cedula", "identificacion"),
            FieldSpec.required("nombre", "Ana María Pérez Gómez", "nombres", "nombre_completo"),
            FieldSpec.optional("correo", "ana.perez@correo.com", "email", "correo_electronico"),
            FieldSpec.optional("telefono", "3001234567", "celular", "movil"),
            FieldSpec.required("programa", "MED", "codigo_programa"),
            FieldSpec.required("semestre", "3", "semestre_actual"),
            FieldSpec.optional("estado", "ACTIVO"),
            FieldSpec.required("fecha_ingreso", "2025-01-20", "fecha_de_ingreso"),
            FieldSpec.optional("estrato", "3"),
            FieldSpec.optional("trabaja", "no"),
            FieldSpec.optional("personas_a_cargo", "0", "dependientes"),
            FieldSpec.optional("transporte", "TRANSPORTE_PUBLICO", "medio_transporte"),
            FieldSpec.optional("version_consentimiento", "2026.1"));

    private final AcademicDirectory academic;
    private final StudentRegistry students;

    StudentRowHandler(AcademicDirectory academic, StudentRegistry students) {
        this.academic = academic;
        this.students = students;
    }

    @Override
    public ImportJob.Type type() {
        return ImportJob.Type.STUDENTS;
    }

    @Override
    public List<FieldSpec> fields() {
        return FIELDS;
    }

    @Override
    public RowOutcome process(RowValues row, Context context) {
        DocumentType documentType = row.enumValue("tipo_documento", DocumentType.class, null);
        String document = row.required("documento");
        if (document != null && !DOCUMENT.matcher(document).matches()) {
            row.addError("documento: formato inválido");
        }
        String name = row.required("nombre");
        String email = row.optional("correo");
        if (email != null && !EMAIL.matcher(email).matches()) {
            row.addError("correo: formato inválido");
        }
        String programCode = row.required("programa");
        Optional<ProgramInfo> program = programCode == null ? Optional.empty() : academic.findProgramByCode(programCode);
        if (programCode != null && program.isEmpty()) {
            row.addError("programa: no existe");
        }
        Integer semester = row.requiredInt("semestre", 1, 14);
        StudentStatus status = row.enumValue("estado", StudentStatus.class, StudentStatus.ACTIVO);
        LocalDate admission = row.requiredDate("fecha_ingreso");
        Integer stratum = row.optionalInt("estrato", 1, 6);
        Boolean works = row.optionalBool("trabaja");
        Integer dependents = row.optionalInt("personas_a_cargo", 0, 20);
        TransportMode transport = row.optional("transporte") == null ? null
                : row.enumValue("transporte", TransportMode.class, TransportMode.OTRO);
        if (document != null) {
            context.firstOccurrence("doc:" + document.replaceAll("[^A-Za-z0-9]", "").toUpperCase(), row);
        }
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        if (context.dryRun()) {
            return RowOutcome.of(RowOutcome.Result.VALID);
        }
        StudentRecord record = new StudentRecord(documentType, document, name, email, row.optional("telefono"),
                program.get().id(), semester, status, admission, stratum, works, dependents, transport,
                row.optional("version_consentimiento"));
        return RowOutcome.of(RowOutcome.Result.valueOf(students.upsert(record, context.actor()).name()));
    }
}

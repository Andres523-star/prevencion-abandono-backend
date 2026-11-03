package co.edu.retencion.imports.application;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.RowOutcome;
import co.edu.retencion.imports.domain.RowValues;
import co.edu.retencion.users.UserDirectory;

/**
 * Docentes: crea el usuario (antes de su primer ingreso) con rol DOCENTE. Clave natural: correo.
 * Nunca quita roles que la persona ya tenga.
 */
@Component
class TeacherRowHandler implements RowHandler {

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.required("correo", "docente@universidad.edu.co", "email", "correo_electronico"),
            FieldSpec.required("nombre", "Carlos Ramírez", "nombres", "nombre_completo"));

    private final UserDirectory users;

    TeacherRowHandler(UserDirectory users) {
        this.users = users;
    }

    @Override
    public ImportJob.Type type() {
        return ImportJob.Type.TEACHERS;
    }

    @Override
    public List<FieldSpec> fields() {
        return FIELDS;
    }

    @Override
    public RowOutcome process(RowValues row, Context context) {
        String email = row.required("correo");
        if (email != null && !StudentRowHandler.EMAIL.matcher(email).matches()) {
            row.addError("correo: formato inválido");
        }
        String name = row.required("nombre");
        if (email != null) {
            context.firstOccurrence("email:" + email.toLowerCase(Locale.ROOT), row);
        }
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        if (context.dryRun()) {
            return RowOutcome.of(RowOutcome.Result.VALID);
        }
        UserDirectory.StaffUpsert result = users.upsertStaff(email, name, Role.DOCENTE, context.actor());
        return RowOutcome.of(RowOutcome.Result.valueOf(result.outcome().name()));
    }
}

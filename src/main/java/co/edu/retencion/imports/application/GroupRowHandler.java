package co.edu.retencion.imports.application;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicCatalog;
import co.edu.retencion.academic.AcademicCatalog.GroupCommand;
import co.edu.retencion.academic.AcademicCatalog.ScheduleSlot;
import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.academic.GroupType;
import co.edu.retencion.academic.ProgramInfo;
import co.edu.retencion.academic.TermInfo;
import co.edu.retencion.imports.domain.ColumnMapping;
import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.RowOutcome;
import co.edu.retencion.imports.domain.RowValues;
import co.edu.retencion.users.UserDirectory;

/**
 * Grupos con su horario. Clave natural: programa + materia + periodo + grupo.
 * Formato del horario: {@code "LUN 07:00-09:00 A-101; MIE 07:00-09:00 A-101"} (aula opcional).
 */
@Component
class GroupRowHandler implements RowHandler {

    private static final Map<String, DayOfWeek> DAYS = Map.of("lun", DayOfWeek.MONDAY, "mar", DayOfWeek.TUESDAY,
            "mie", DayOfWeek.WEDNESDAY, "jue", DayOfWeek.THURSDAY, "vie", DayOfWeek.FRIDAY,
            "sab", DayOfWeek.SATURDAY, "dom", DayOfWeek.SUNDAY);
    private static final Pattern SLOT = Pattern.compile("^(\\p{L}{3})\\p{L}*\\s+(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})(?:\\s+(.+))?$");

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.required("programa", "MED", "codigo_programa"),
            FieldSpec.required("materia", "MED-201", "codigo_materia"),
            FieldSpec.required("periodo", "2026-2", "codigo_periodo"),
            FieldSpec.required("grupo", "A", "codigo_grupo"),
            FieldSpec.required("cupo", "40", "capacidad"),
            FieldSpec.optional("correo_docente", "docente@universidad.edu.co", "docente"),
            FieldSpec.optional("tipo", "REGULAR", "tipo_grupo"),
            FieldSpec.optional("horario", "LUN 07:00-09:00 A-101; MIE 07:00-09:00 A-101"));

    private final AcademicDirectory directory;
    private final AcademicCatalog catalog;
    private final UserDirectory users;

    GroupRowHandler(AcademicDirectory directory, AcademicCatalog catalog, UserDirectory users) {
        this.directory = directory;
        this.catalog = catalog;
        this.users = users;
    }

    @Override
    public ImportJob.Type type() {
        return ImportJob.Type.GROUPS;
    }

    @Override
    public List<FieldSpec> fields() {
        return FIELDS;
    }

    @Override
    public RowOutcome process(RowValues row, Context context) {
        String programCode = row.required("programa");
        String subjectCode = row.required("materia");
        String termCode = row.required("periodo");
        String groupCode = row.required("grupo");
        Integer capacity = row.requiredInt("cupo", 1, 1000);
        GroupType type = row.enumValue("tipo", GroupType.class, GroupType.REGULAR);
        List<ScheduleSlot> schedule = parseSchedule(row.optional("horario"), row);

        Optional<ProgramInfo> program = programCode == null ? Optional.empty() : directory.findProgramByCode(programCode);
        if (programCode != null && program.isEmpty()) {
            row.addError("programa: no existe");
        }
        Optional<UUID> subject = program.isPresent() && subjectCode != null
                ? directory.findSubjectId(program.get().id(), subjectCode) : Optional.empty();
        if (program.isPresent() && subjectCode != null && subject.isEmpty()) {
            row.addError("materia: no existe en el programa");
        }
        Optional<TermInfo> term = termCode == null ? Optional.empty() : directory.findTermByCode(termCode);
        if (termCode != null && term.isEmpty()) {
            row.addError("periodo: no existe");
        }
        UUID teacher = null;
        String teacherEmail = row.optional("correo_docente");
        if (teacherEmail != null) {
            teacher = users.findIdByEmail(teacherEmail).orElse(null);
            if (teacher == null) {
                row.addError("correo_docente: no hay un usuario con ese correo (importe primero los docentes)");
            }
        }
        if (programCode != null && subjectCode != null && termCode != null && groupCode != null) {
            context.firstOccurrence(String.join("|", "group", programCode, subjectCode, termCode, groupCode)
                    .toUpperCase(Locale.ROOT), row);
        }
        if (row.hasErrors()) {
            return RowOutcome.error(row.errorMessage());
        }
        if (context.dryRun()) {
            return RowOutcome.of(RowOutcome.Result.VALID);
        }
        AcademicCatalog.Upsert result = catalog.upsertGroup(new GroupCommand(subject.get(), term.get().id(), groupCode,
                teacher, capacity, type, schedule));
        return RowOutcome.of(RowOutcome.Result.valueOf(result.outcome().name()));
    }

    static List<ScheduleSlot> parseSchedule(String value, RowValues row) {
        List<ScheduleSlot> slots = new ArrayList<>();
        if (value == null) {
            return slots;
        }
        for (String part : value.split(";")) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            Matcher m = SLOT.matcher(text);
            DayOfWeek day = m.matches() ? DAYS.get(ColumnMapping.normalize(m.group(1))) : null;
            if (day == null) {
                row.addError("horario: franja inválida (use p. ej. LUN 07:00-09:00 A-101)");
                continue;
            }
            try {
                LocalTime start = LocalTime.parse(pad(m.group(2)));
                LocalTime end = LocalTime.parse(pad(m.group(3)));
                slots.add(new ScheduleSlot(day, start, end, m.group(4) == null ? null : m.group(4).trim()));
            } catch (DateTimeParseException ex) {
                row.addError("horario: hora inválida");
            }
        }
        return slots;
    }

    private static String pad(String time) {
        return time.length() == 4 ? "0" + time : time;
    }
}

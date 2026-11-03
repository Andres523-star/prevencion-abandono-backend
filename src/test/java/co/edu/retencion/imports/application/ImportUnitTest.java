package co.edu.retencion.imports.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import co.edu.retencion.academic.AcademicCatalog.ScheduleSlot;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.imports.domain.ColumnMapping;
import co.edu.retencion.imports.domain.FieldSpec;
import co.edu.retencion.imports.domain.ImportJob;
import co.edu.retencion.imports.domain.RowValues;
import co.edu.retencion.imports.infrastructure.TableReader;
import co.edu.retencion.students.StudentStatus;

/** Mapeo configurable de columnas, conversión de valores, lectores CSV/Excel y horario. */
class ImportUnitTest {

    private static final List<FieldSpec> FIELDS = List.of(
            FieldSpec.required("documento", "1", "cedula"),
            FieldSpec.required("nombre", "x"),
            FieldSpec.optional("correo", "x", "email"));

    @Test
    void headersAreNormalizedAndAliasesResolved() {
        assertThat(ColumnMapping.normalize("﻿ Número de Documento ")).isEqualTo("numero_de_documento");
        var resolution = ColumnMapping.resolve(List.of("Cédula", "NOMBRE", "E-mail"), FIELDS,
                Map.of("correo", List.of("e_mail")), Map.of());
        assertThat(resolution.isComplete()).isTrue();
        assertThat(resolution.columnIndex()).containsEntry("documento", 0).containsEntry("nombre", 1)
                .containsEntry("correo", 2);

        var overridden = ColumnMapping.resolve(List.of("Doc Estudiante", "Nombre"), FIELDS, Map.of(),
                Map.of("documento", "doc estudiante"));
        assertThat(overridden.columnIndex()).containsEntry("documento", 0);

        var missing = ColumnMapping.resolve(List.of("nombre"), FIELDS, Map.of(), Map.of());
        assertThat(missing.missingRequired()).containsExactly("documento");
    }

    @Test
    void rowValuesParseAndCollectErrorsWithoutEchoingValues() {
        RowValues ok = new RowValues(2, Map.of("n", "3", "b", "Sí", "d", "20/01/2026", "e", "aplazado"));
        assertThat(ok.requiredInt("n", 1, 14)).isEqualTo(3);
        assertThat(ok.optionalBool("b")).isTrue();
        assertThat(ok.requiredDate("d")).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(ok.enumValue("e", StudentStatus.class, null)).isEqualTo(StudentStatus.APLAZADO);
        assertThat(ok.hasErrors()).isFalse();

        RowValues bad = new RowValues(3, Map.of("n", "99", "b", "quizás", "d", "ayer", "e", "1020304050", "s", "abc"));
        bad.requiredInt("n", 1, 14);
        bad.optionalBool("b");
        bad.requiredDate("d");
        bad.enumValue("e", StudentStatus.class, null);
        bad.optionalInt("s", 0, 5);
        bad.required("falta");
        assertThat(bad.hasErrors()).isTrue();
        assertThat(bad.errorMessage()).contains("n:", "b:", "d:", "e:", "s:", "falta: es obligatorio")
                .doesNotContain("1020304050").doesNotContain("quizás");
    }

    @Test
    void csvReaderHandlesBomAndSemicolons() {
        byte[] csv = "﻿documento;nombre\n123;\"Pérez; Ana\"\n\n456;Luis\n".getBytes(StandardCharsets.UTF_8);
        TableReader.Table table = TableReader.forFormat(ImportJob.Format.CSV).read(csv);
        assertThat(table.headers()).containsExactly("documento", "nombre");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).cells()).containsExactly("123", "Pérez; Ana");
        assertThat(table.rows().get(1).number()).isEqualTo(4);
    }

    @Test
    void xlsxReaderReadsFirstSheet() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        XSSFSheet sheet = workbook.createSheet("datos");
        sheet.createRow(0).createCell(0).setCellValue("documento");
        sheet.getRow(0).createCell(1).setCellValue("semestre");
        sheet.createRow(1).createCell(0).setCellValue("123");
        sheet.getRow(1).createCell(1).setCellValue(3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        TableReader.Table table = TableReader.forFormat(ImportJob.Format.XLSX).read(out.toByteArray());
        assertThat(table.headers()).containsExactly("documento", "semestre");
        assertThat(table.rows().get(0).cells()).containsExactly("123", "3");
        assertThatThrownBy(() -> TableReader.forFormat(ImportJob.Format.XLSX).read("no es excel".getBytes()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void formatIsDetectedByMagicBytesNotExtension() {
        assertThat(ImportService.detectFormat("datos.csv", "a,b".getBytes())).isEqualTo(ImportJob.Format.CSV);
        assertThat(ImportService.detectFormat("x.bin", new byte[] {'P', 'K', 3, 4, 0})).isEqualTo(ImportJob.Format.XLSX);
        assertThatThrownBy(() -> ImportService.detectFormat("falso.xlsx", "a,b".getBytes()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ImportService.detectFormat("binario.csv", new byte[] {1, 0, 2}))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void scheduleTextIsParsed() {
        RowValues row = new RowValues(2, Map.of());
        List<ScheduleSlot> slots = GroupRowHandler.parseSchedule("LUN 7:00-9:00 A-101; Miércoles 14:00-16:00", row);
        assertThat(row.hasErrors()).isFalse();
        assertThat(slots).containsExactly(
                new ScheduleSlot(DayOfWeek.MONDAY, LocalTime.of(7, 0), LocalTime.of(9, 0), "A-101"),
                new ScheduleSlot(DayOfWeek.WEDNESDAY, LocalTime.of(14, 0), LocalTime.of(16, 0), null));
        GroupRowHandler.parseSchedule("XYZ 7-9", row);
        assertThat(row.hasErrors()).isTrue();
    }
}

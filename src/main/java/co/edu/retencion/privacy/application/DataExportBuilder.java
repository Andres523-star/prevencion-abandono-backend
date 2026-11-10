package co.edu.retencion.privacy.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.stereotype.Component;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.justifications.JustificationQueries;
import co.edu.retencion.records.StudentRecords;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;

/**
 * Copia de los datos del titular (derecho de acceso) en JSON legible por máquina. Incluye lo que la
 * plataforma guarda de él: identidad, datos académicos, faltas, notas, justificaciones y consentimientos.
 * No incluye evaluaciones de bienestar (se entregan por el canal clínico, ver docs/cumplimiento-ley-1581.md)
 * ni el razonamiento interno del motor de riesgo.
 */
@Component
class DataExportBuilder {

    private final StudentRegistry students;
    private final StudentRecords records;
    private final JustificationQueries justifications;
    private final AcademicDirectory academic;
    private final ObjectMapper mapper;
    private final Clock clock;

    DataExportBuilder(StudentRegistry students, StudentRecords records, JustificationQueries justifications,
                      AcademicDirectory academic, ObjectMapper mapper, Clock clock) {
        this.students = students;
        this.records = records;
        this.justifications = justifications;
        this.academic = academic;
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock;
    }

    byte[] build(UUID studentId) {
        StudentInfo student = students.findById(studentId).orElseThrow();
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("generadoEn", clock.instant().toString());
        export.put("responsable", "Universidad (responsable del tratamiento)");
        export.put("identidad", students.identityOf(studentId).map(i -> Map.of(
                "nombre", i.fullName(), "tipoDocumento", i.documentType(), "documento", i.documentNumber(),
                "correo", String.valueOf(i.email()), "telefono", String.valueOf(i.phone()))).orElse(null));
        Map<String, Object> academicData = new LinkedHashMap<>();
        academicData.put("codigo", student.anonCode());
        academicData.put("programa", academic.findProgram(student.programId()).map(p -> p.name()).orElse(null));
        academicData.put("semestre", student.currentSemester());
        academicData.put("estado", student.status().name());
        academicData.put("resumen", records.snapshot(studentId, null));
        export.put("academico", academicData);
        export.put("faltas", records.absences(studentId));
        export.put("justificaciones", justifications.ofStudent(studentId));
        export.put("consentimientos", students.consentsOf(studentId));
        try {
            return mapper.writeValueAsBytes(export);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("No se pudo armar la copia de datos", ex);
        }
    }
}

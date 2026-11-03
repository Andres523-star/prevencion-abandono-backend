package co.edu.retencion.students;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import co.edu.retencion.common.data.UpsertOutcome;

/**
 * API pública del módulo de estudiantes: consultas sin identidad y altas idempotentes para la
 * importación masiva (clave natural: número de documento, vía blind index).
 */
public interface StudentRegistry {

    Optional<StudentInfo> findById(UUID studentId);

    Optional<UUID> findIdByDocument(String documentNumber);

    /** Crea o actualiza el estudiante con su identidad y perfil socioeconómico. */
    UpsertOutcome upsert(StudentRecord record, UUID actor);

    /** Inscribe al estudiante en el grupo (idempotente; reactiva una inscripción retirada). */
    UpsertOutcome enroll(UUID studentId, UUID groupId);

    /**
     * Datos de un estudiante para alta o actualización.
     *
     * @param documentType   tipo de documento
     * @param documentNumber número de documento (clave natural)
     * @param fullName       nombre completo
     * @param email          correo (opcional, usado para avisos y para vincular su cuenta)
     * @param phone          teléfono (opcional)
     * @param programId      programa
     * @param semester       semestre actual
     * @param status         estado
     * @param admissionDate  fecha de ingreso
     * @param stratum        estrato socioeconómico (opcional)
     * @param works          trabaja (opcional)
     * @param dependents     personas a cargo (opcional)
     * @param transport      transporte (opcional)
     * @param consentVersion versión de la política aceptada (opcional; registra consentimiento)
     */
    record StudentRecord(DocumentType documentType, String documentNumber, String fullName, String email,
                         String phone, UUID programId, int semester, StudentStatus status, LocalDate admissionDate,
                         Integer stratum, Boolean works, Integer dependents, TransportMode transport,
                         String consentVersion) {

        @Override
        public String toString() {
            return "StudentRecord[programId=" + programId + ", semester=" + semester + "]";
        }
    }
}

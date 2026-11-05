package co.edu.retencion.students;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    Optional<EnrollmentInfo> findEnrollment(UUID enrollmentId);

    Optional<EnrollmentInfo> findEnrollment(UUID studentId, UUID groupId);

    /** Inscripciones activas del grupo (lista de clase para tomar asistencia o notas). */
    List<EnrollmentInfo> activeEnrollments(UUID groupId);

    Optional<UUID> findStudentIdByUser(UUID userId);

    /** Todas las inscripciones del estudiante (activas y retiradas), de la más reciente a la más antigua. */
    List<EnrollmentInfo> enrollmentsOf(UUID studentId);

    /** Estudiantes con alguna inscripción en el periodo (dataset de entrenamiento). */
    Set<UUID> studentIdsEnrolledInTerm(UUID termId);

    /**
     * Ids de estudiantes ACTIVOS del programa en orden, por keyset (lote nocturno del motor de riesgo,
     * particionado por programa).
     *
     * @param after último id de la página anterior, o {@code null} para empezar
     */
    List<UUID> activeStudentIds(UUID programId, UUID after, int limit);

    /**
     * Perfil socioeconómico sin identificadores. Solo para el motor de riesgo, que lo usa con
     * cuidado de sesgo (el estrato nunca suma puntaje) y nunca lo envía a la IA.
     */
    Optional<SocioeconomicInfo> socioeconomic(UUID studentId);

    /**
     * Primer nombre del estudiante, para rellenar el marcador {@code {{nombre}}} de los textos
     * dirigidos a él. Es un dato personal: el llamador debe auditar la lectura y nunca enviarlo a
     * la IA ni a los logs.
     */
    Optional<String> givenName(UUID studentId);

    /**
     * Nombre completo del estudiante (campanita del docente, vista del chat). Dato personal: el
     * llamador verifica el alcance, audita la lectura y nunca lo envía a la IA ni a los logs.
     */
    Optional<String> displayName(UUID studentId);

    /** Cambia el estado académico (cierre de un caso de retención). El llamador audita la operación. */
    void changeStatus(UUID studentId, StudentStatus status);

    /**
     * Correo del estudiante para los avisos de faltas (único uso de correo de la plataforma). Lo usa
     * solo el módulo de correo; nunca debe registrarse en logs.
     */
    Optional<Contact> notificationContact(UUID studentId);

    /**
     * @param email           correo descifrado
     * @param emailBlindIndex HMAC del correo (para supresiones por rebote)
     */
    record Contact(String email, String emailBlindIndex) {

        @Override
        public String toString() {
            return "Contact[***]";
        }
    }

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

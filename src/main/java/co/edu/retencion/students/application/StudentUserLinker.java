package co.edu.retencion.students.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import co.edu.retencion.students.domain.StudentIdentityRepository;
import co.edu.retencion.students.domain.StudentRepository;
import co.edu.retencion.users.UserDirectory;
import co.edu.retencion.users.UserProvisioned;

/**
 * Vínculo usuario-estudiante automático: cuando se crea una cuenta (primer ingreso o alta por un
 * ADMIN), si su correo coincide (por blind index) con el de un estudiante no vinculado, se enlazan
 * y la cuenta recibe el rol ESTUDIANTE. El evento solo trae el id: el correo nunca viaja en claro.
 */
@Component
class StudentUserLinker {

    private static final Logger log = LoggerFactory.getLogger(StudentUserLinker.class);

    private final UserDirectory users;
    private final StudentIdentityRepository identities;
    private final StudentRepository students;
    private final StudentWriter writer;

    StudentUserLinker(UserDirectory users, StudentIdentityRepository identities, StudentRepository students,
                      StudentWriter writer) {
        this.users = users;
        this.identities = identities;
        this.students = students;
        this.writer = writer;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(UserProvisioned event) {
        try {
            users.emailBlindIndexOf(event.userId()).ifPresent(index -> identities.findByEmailBlindIndex(index).stream()
                    .map(identity -> students.findById(identity.getStudentId()))
                    .flatMap(java.util.Optional::stream)
                    .filter(student -> student.getUserId() == null)
                    .findFirst()
                    .ifPresent(student -> writer.linkUserByEmail(student, index)));
        } catch (RuntimeException ex) {
            // No debe impedir el inicio de sesión; se puede vincular manualmente (PUT /students/{id}/user).
            log.warn("No se pudo vincular automáticamente la cuenta con un estudiante", ex);
        }
    }
}

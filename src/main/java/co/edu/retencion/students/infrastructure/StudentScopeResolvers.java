package co.edu.retencion.students.infrastructure;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.common.security.authorization.ResourceScope;
import co.edu.retencion.common.security.authorization.ResourceScopeResolver;
import co.edu.retencion.students.domain.EnrollmentRepository;
import co.edu.retencion.students.domain.StudentRepository;

/**
 * Atributos ABAC del estudiante: su programa, los grupos donde está inscrito (para que el docente
 * solo vea a sus estudiantes) y su usuario titular (para que el estudiante solo vea lo suyo).
 */
@Configuration(proxyBeanMethods = false)
public class StudentScopeResolvers {

    @Bean
    ResourceScopeResolver studentScopeResolver(StudentRepository students, EnrollmentRepository enrollments) {
        return new ResourceScopeResolver() {
            @Override
            public String resourceType() {
                return "STUDENT";
            }

            @Override
            public Optional<ResourceScope> resolve(UUID id) {
                return students.findById(id).map(s -> new ResourceScope("STUDENT", id, Set.of(s.getProgramId()),
                        enrollments.findActiveGroupIds(id), s.getUserId()));
            }
        };
    }

    @Bean
    ResourceScopeResolver enrollmentScopeResolver(EnrollmentRepository enrollments, StudentRepository students,
                                                  AcademicDirectory academic) {
        return new ResourceScopeResolver() {
            @Override
            public String resourceType() {
                return "ENROLLMENT";
            }

            @Override
            public Optional<ResourceScope> resolve(UUID id) {
                return enrollments.findById(id).flatMap(e -> academic.findGroup(e.getGroupId()).map(g ->
                        new ResourceScope("ENROLLMENT", id, Set.of(g.programId()), Set.of(g.id()),
                                students.findById(e.getStudentId()).map(s -> s.getUserId()).orElse(null))));
            }
        };
    }
}

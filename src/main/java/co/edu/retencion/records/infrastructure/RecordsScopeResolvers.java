package co.edu.retencion.records.infrastructure;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.retencion.academic.AcademicDirectory;
import co.edu.retencion.common.security.authorization.ResourceScope;
import co.edu.retencion.common.security.authorization.ResourceScopeResolver;
import co.edu.retencion.records.domain.GradeRepository;
import co.edu.retencion.records.domain.StudentCauseRepository;
import co.edu.retencion.students.StudentInfo;
import co.edu.retencion.students.StudentRegistry;

/** Atributos ABAC de una nota (su grupo y su estudiante) y de una causa asociada a un estudiante. */
@Configuration(proxyBeanMethods = false)
public class RecordsScopeResolvers {

    @Bean
    ResourceScopeResolver gradeScopeResolver(GradeRepository grades, AcademicDirectory academic,
                                             StudentRegistry students) {
        return resolver("GRADE", id -> grades.findById(id).flatMap(g -> academic.findGroup(g.getGroupId()).map(group ->
                new ResourceScope("GRADE", id, Set.of(group.programId()), Set.of(group.id()),
                        students.findById(g.getStudentId()).map(StudentInfo::userId).orElse(null)))));
    }

    @Bean
    ResourceScopeResolver studentCauseScopeResolver(StudentCauseRepository causes, StudentRegistry students) {
        return resolver("STUDENT_CAUSE", id -> causes.findById(id).flatMap(c -> students.findById(c.getStudentId()))
                .map(s -> new ResourceScope("STUDENT_CAUSE", id, Set.of(s.programId()), Set.of(), s.userId())));
    }

    private static ResourceScopeResolver resolver(String type, Function<UUID, Optional<ResourceScope>> fn) {
        return new ResourceScopeResolver() {
            @Override
            public String resourceType() {
                return type;
            }

            @Override
            public Optional<ResourceScope> resolve(UUID resourceId) {
                return fn.apply(resourceId);
            }
        };
    }
}

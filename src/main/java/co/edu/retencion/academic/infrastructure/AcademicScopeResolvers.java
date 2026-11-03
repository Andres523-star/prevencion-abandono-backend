package co.edu.retencion.academic.infrastructure;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.retencion.academic.domain.ClassSessionRepository;
import co.edu.retencion.academic.domain.CourseGroupRepository;
import co.edu.retencion.academic.domain.ProgramRepository;
import co.edu.retencion.academic.domain.SubjectRepository;
import co.edu.retencion.common.security.authorization.ResourceScope;
import co.edu.retencion.common.security.authorization.ResourceScopeResolver;

/**
 * Atributos ABAC de los recursos académicos: PROGRAM, SUBJECT, GROUP y SESSION. Con ellos un
 * coordinador solo administra su programa y un docente solo sus grupos.
 */
@Configuration(proxyBeanMethods = false)
public class AcademicScopeResolvers {

    @Bean
    ResourceScopeResolver programScopeResolver(ProgramRepository programs) {
        return resolver("PROGRAM", id -> programs.findById(id)
                .map(p -> new ResourceScope("PROGRAM", id, Set.of(id), Set.of(), null)));
    }

    @Bean
    ResourceScopeResolver subjectScopeResolver(SubjectRepository subjects) {
        return resolver("SUBJECT", id -> subjects.findById(id)
                .map(s -> new ResourceScope("SUBJECT", id, Set.of(s.getProgramId()), Set.of(), null)));
    }

    @Bean
    ResourceScopeResolver groupScopeResolver(CourseGroupRepository groups, SubjectRepository subjects) {
        return resolver("GROUP", id -> groups.findById(id).flatMap(g -> subjects.findById(g.getSubjectId())
                .map(s -> new ResourceScope("GROUP", id, Set.of(s.getProgramId()), Set.of(id), null))));
    }

    @Bean
    ResourceScopeResolver sessionScopeResolver(ClassSessionRepository sessions, CourseGroupRepository groups,
                                               SubjectRepository subjects) {
        return resolver("SESSION", id -> sessions.findById(id).flatMap(s -> groups.findById(s.getGroupId()))
                .flatMap(g -> subjects.findById(g.getSubjectId())
                        .map(sub -> new ResourceScope("SESSION", id, Set.of(sub.getProgramId()), Set.of(g.getId()), null))));
    }

    private static ResourceScopeResolver resolver(String type, java.util.function.Function<UUID, Optional<ResourceScope>> fn) {
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

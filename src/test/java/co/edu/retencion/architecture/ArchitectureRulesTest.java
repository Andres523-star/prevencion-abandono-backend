package co.edu.retencion.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;

import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;

/**
 * Reglas de arquitectura del CLAUDE.md verificadas con ArchUnit.
 */
@AnalyzeClasses(packages = "co.edu.retencion", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule domainIsIndependentOfOuterLayers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..api..", "..application..", "..infrastructure..")
            .because("la capa de dominio no debe conocer controladores ni infraestructura");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .because("los controladores pasan por la capa de aplicación");

    @ArchTest
    static final ArchRule entitiesLiveInDomain = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..domain..");

    @ArchTest
    static final ArchRule controllersNeverReturnEntities = methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().arePublic()
            .should(notReturnJpaEntities())
            .because("las entidades JPA nunca salen por la API: siempre DTO");

    @ArchTest
    static final ArchRule writesAreAudited = methods()
            .that().areAnnotatedWith(PostMapping.class)
            .or().areAnnotatedWith(PutMapping.class)
            .or().areAnnotatedWith(PatchMapping.class)
            .or().areAnnotatedWith(DeleteMapping.class)
            .should().beAnnotatedWith(Audited.class)
            .because("toda escritura debe quedar en la auditoría");

    @ArchTest
    static final ArchRule noStandardStreams = noClasses()
            .that().doNotHaveSimpleName("BreakGlassSecretGenerator")
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .because("se usan logs estructurados (y sin PII), no System.out");

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    private static ArchCondition<JavaMethod> notReturnJpaEntities() {
        DescribedPredicate<JavaClass> entity = DescribedPredicate.describe("entity",
                c -> c.isAnnotatedWith(Entity.class));
        return new ArchCondition<>("not return JPA entities") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean returnsEntity = entity.test(method.getRawReturnType())
                        || method.getReturnType().getAllInvolvedRawTypes().stream().anyMatch(entity);
                if (returnsEntity) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName() + " devuelve una entidad JPA"));
                }
            }
        };
    }
}

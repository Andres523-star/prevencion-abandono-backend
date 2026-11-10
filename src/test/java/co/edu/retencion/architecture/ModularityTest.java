package co.edu.retencion.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import co.edu.retencion.RetencionApplication;

/**
 * Criterio de aceptación: límites de módulos verificados por Spring Modulith. Falla si un
 * módulo usa clases internas de otro (subpaquetes) o si hay dependencias circulares.
 * Además genera la documentación de módulos (PlantUML) en target/spring-modulith-docs.
 */
class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(RetencionApplication.class);

    @Test
    void moduleBoundariesAreRespected() {
        MODULES.verify();
    }

    @Test
    void expectedModulesExist() {
        assertThat(MODULES.stream().map(ApplicationModule::getName))
                .containsExactlyInAnyOrder("common", "auth", "users", "audit", "settings", "academic", "students", "imports",
                        "records", "justifications", "mailing", "wellbeing", "ai", "risk",
                        "realtime", "alerts", "notifications", "chat", "interventions", "retention",
                        "analytics", "dashboard", "reports", "portal", "privacy");
    }

    @Test
    void writeModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}

package co.edu.retencion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import co.edu.retencion.common.security.Role;
import co.edu.retencion.students.infrastructure.SyntheticDataGenerator;
import co.edu.retencion.support.AnalyticsDataset.Dataset;
import co.edu.retencion.support.AttendanceWorld.Actor;

/**
 * Base de las pruebas de la Fase 6. Las vistas son compartidas y otras pruebas también escriben datos:
 * se genera un periodo sintético de 1.200 estudiantes una sola vez y se filtra por él, lo que aísla los
 * resultados y permite compararlos con las tablas base.
 */
public abstract class AnalyticsIntegrationTest extends CampusIntegrationTest {

    private static Dataset dataset;

    @Autowired
    protected SyntheticDataGenerator generator;

    protected Dataset dataset() throws Exception {
        if (dataset == null) {
            dataset = AnalyticsDataset.create(generator, jdbc, 1200);
            refresh();
        }
        return dataset;
    }

    protected Actor admin() throws Exception {
        return staff(Set.of(Role.ADMIN), Set.of());
    }

    protected void refresh() throws Exception {
        Actor admin = admin();
        mvc.perform(post("/api/v1/analytics/refresh").header("Authorization", bearer(admin.tokens())))
                .andExpect(status().isOk());
    }

    protected MockHttpServletRequestBuilder chart(Actor actor, String path) {
        return get("/api/v1/analytics/" + path).header("Authorization", bearer(actor.tokens()));
    }

    protected JsonNode rows(Actor actor, String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = chart(actor, path);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return read(mvc.perform(request).andExpect(status().isOk()));
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}

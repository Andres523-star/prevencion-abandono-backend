package co.edu.retencion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.support.AttendanceWorld.Actor;

/**
 * Utilidades de la Fase 5: aislar al personal que recibe asignaciones automáticas (para que la
 * prueba sepa a quién le llega la alerta o el caso), leer la campanita y subir adjuntos del chat.
 */
public abstract class CampusIntegrationTest extends RiskIntegrationTest {

    /** Espera explícita a los listeners (antes de consultar la base directamente o llamar servicios). */
    protected void awaitListeners() {
        listeners.await();
    }

    /**
     * Desactiva a todo el personal de consejería, bienestar y coordinación creado por otras pruebas,
     * salvo los indicados: las asignaciones automáticas (menor carga) quedan determinadas.
     */
    protected void onlyStaff(Actor... keep) {
        List<UUID> ids = new ArrayList<>();
        for (Actor a : keep) {
            ids.add(a.userId());
        }
        ids.add(UUID.randomUUID());
        jdbc.update("""
                UPDATE users SET status = 'DISABLED' WHERE id IN
                  (SELECT user_id FROM user_roles WHERE role_code IN ('CONSEJERO', 'PSICOLOGO', 'COORDINADOR'))
                  AND NOT (id = ANY (?))
                """, (Object) ids.toArray(new UUID[0]));
    }

    protected JsonNode notifications(Actor actor) throws Exception {
        awaitListeners();
        relogin(actor);
        return read(mvc.perform(get("/api/v1/notifications").header("Authorization", bearer(actor.tokens())))
                .andExpect(status().isOk())).get("items");
    }

    protected ResultActions upload(Actor actor, UUID conversationId, String name, byte[] content, Integer duration)
            throws Exception {
        var request = multipart("/api/v1/chats/" + conversationId + "/attachments")
                .file(new MockMultipartFile("file", name, "application/octet-stream", content))
                .header("Authorization", bearer(actor.tokens()));
        if (duration != null) {
            request.param("durationSeconds", String.valueOf(duration));
        }
        return mvc.perform(request);
    }
}

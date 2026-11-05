package co.edu.retencion.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import co.edu.retencion.support.AttendanceWorld.Actor;

/**
 * Utilidades de la Fase 5: aislar al personal que recibe asignaciones automáticas (para que la
 * prueba sepa a quién le llega la alerta o el caso), leer la campanita y subir adjuntos del chat.
 */
public abstract class CampusIntegrationTest extends RiskIntegrationTest {

    /** Publicaciones pendientes antes de la prueba (fallidas de otras pruebas): no se esperan. */
    private final Set<String> pendingBefore = new HashSet<>();

    @BeforeEach
    void snapshotPendingPublications() {
        pendingBefore.clear();
        pendingBefore.addAll(pendingPublications());
    }

    private List<String> pendingPublications() {
        return jdbc.queryForList("SELECT id::text FROM event_publication WHERE completion_date IS NULL", String.class);
    }

    /**
     * Espera a que terminen los listeners de eventos de esta prueba. Spring Modulith habilita
     * {@code @EnableAsync}, así que los {@code @ApplicationModuleListener} corren en otro hilo; como
     * las publicaciones completadas se borran ({@code completion-mode: delete}), basta con esperar a
     * que no quede ninguna pendiente nueva (una cadena de eventos siempre deja al menos una).
     */
    protected void awaitListeners() throws InterruptedException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (pendingBefore.containsAll(pendingPublications())) {
                return;
            }
            Thread.sleep(25);
        }
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

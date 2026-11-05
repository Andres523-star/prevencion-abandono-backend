package co.edu.retencion.users.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.users.application.AppointmentLinkService;

/** Enlace de citas del docente (solo el propio docente). */
@RestController
@RequestMapping("/api/v1/users/me/appointment-link")
@Tag(name = "Usuarios")
@PreAuthorize("hasRole('DOCENTE')")
public class AppointmentLinkController {

    private final AppointmentLinkService links;

    public AppointmentLinkController(AppointmentLinkService links) {
        this.links = links;
    }

    public record LinkRequest(@NotBlank @Size(max = 500) String url) {
    }

    public record LinkView(String url) {
    }

    @GetMapping
    @Operation(summary = "Mi enlace de citas (para copiarlo y enviarlo al estudiante)")
    public ResponseEntity<LinkView> mine() {
        return links.mine().map(l -> ResponseEntity.ok(new LinkView(l))).orElse(ResponseEntity.noContent().build());
    }

    @PutMapping
    @Audited(action = "APPOINTMENT_LINK_UPDATE", entity = "User")
    @Operation(summary = "Registra mi enlace de citas (https y dominio permitido)")
    public LinkView update(@Valid @RequestBody LinkRequest request) {
        return new LinkView(links.update(request.url()));
    }

    @DeleteMapping
    @Audited(action = "APPOINTMENT_LINK_DELETE", entity = "User")
    @Operation(summary = "Quita mi enlace de citas")
    public ResponseEntity<Void> clear() {
        links.clear();
        return ResponseEntity.noContent().build();
    }
}

package co.edu.retencion.mailing.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.mailing.application.MailDeliveryService;
import co.edu.retencion.mailing.application.MailDeliveryService.DeliveryView;
import co.edu.retencion.mailing.application.MailDispatcher;
import co.edu.retencion.mailing.application.MailDispatcher.DispatchReport;
import co.edu.retencion.mailing.domain.EmailOutbox;

/**
 * Registro de entrega de correos (ADMIN), despacho manual y webhook de rebotes del proveedor
 * (público, autenticado por firma HMAC en {@code X-Mail-Signature}).
 */
@RestController
@RequestMapping("/api/v1/mail")
@Tag(name = "Correo")
public class MailController {

    private final MailDeliveryService deliveries;
    private final MailDispatcher dispatcher;
    private final ObjectMapper json;

    public MailController(MailDeliveryService deliveries, MailDispatcher dispatcher, ObjectMapper json) {
        this.deliveries = deliveries;
        this.dispatcher = dispatcher;
        this.json = json;
    }

    @GetMapping("/outbox")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Registro de entrega (sin direcciones de correo)")
    public List<DeliveryView> outbox(@RequestParam(required = false) EmailOutbox.Status status,
                                     @RequestParam(required = false) UUID studentId,
                                     @RequestParam(defaultValue = "100") int limit) {
        return deliveries.list(status, studentId, limit);
    }

    @PostMapping("/dispatch")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "MAIL_DISPATCH_RUN", entity = "EmailOutbox")
    @Operation(summary = "Ejecuta ahora un ciclo de despacho (normalmente es periódico)")
    public DispatchReport dispatch() {
        return dispatcher.dispatch();
    }

    /** Cuerpo esperado: {@code {"providerMessageId": "...", "reason": "..."}}. */
    @PostMapping("/bounces")
    @Audited(action = "MAIL_BOUNCE", entity = "EmailOutbox")
    @Operation(summary = "Webhook de rebotes del proveedor de correo (firma HMAC-SHA256)")
    public ResponseEntity<Void> bounce(@RequestBody String body,
                                       @RequestHeader(value = "X-Mail-Signature", required = false) String signature) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        if (!deliveries.validSignature(raw, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode node;
        try {
            node = json.readTree(raw);
        } catch (java.io.IOException ex) {
            throw DomainException.badRequest("Cuerpo del webhook inválido");
        }
        String providerMessageId = node.path("providerMessageId").asText("");
        if (providerMessageId.isBlank()) {
            throw DomainException.badRequest("Falta providerMessageId");
        }
        deliveries.bounce(providerMessageId, node.path("reason").asText("rebote"));
        return ResponseEntity.noContent().build();
    }
}

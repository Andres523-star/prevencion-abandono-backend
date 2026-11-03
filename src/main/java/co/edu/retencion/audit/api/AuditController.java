package co.edu.retencion.audit.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.application.AuditChainVerifier;
import co.edu.retencion.audit.domain.ChainVerification;

/** Endpoints de administración de la auditoría. */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Auditoría")
public class AuditController {

    private final AuditChainVerifier verifier;

    public AuditController(AuditChainVerifier verifier) {
        this.verifier = verifier;
    }

    @GetMapping("/verify")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Verifica la integridad de la cadena de hashes de la auditoría")
    public ChainVerification verify() {
        return verifier.verify();
    }
}

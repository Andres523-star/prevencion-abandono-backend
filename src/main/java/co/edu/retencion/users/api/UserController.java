package co.edu.retencion.users.api;

import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.audit.SensitiveRead;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.common.security.authorization.RequireScope;
import co.edu.retencion.common.web.CursorPage;
import co.edu.retencion.users.UserStatus;
import co.edu.retencion.users.application.UserAdminService;
import co.edu.retencion.users.application.UserViews.MeView;
import co.edu.retencion.users.application.UserViews.UserDetailView;
import co.edu.retencion.users.application.UserViews.UserSummaryView;

/**
 * Endpoints de usuarios. Combina RBAC ({@code @PreAuthorize}) y ABAC ({@code @RequireScope}).
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Usuarios")
public class UserController {

    private final UserAdminService service;

    public UserController(UserAdminService service) {
        this.service = service;
    }

    /**
     * @param roles      roles que tendrá el usuario (reemplaza los actuales)
     * @param programIds programas en su alcance
     */
    public record UpdateRolesRequest(@NotNull @Size(max = 6) Set<Role> roles,
                                     @NotNull @Size(max = 50) Set<UUID> programIds) {
    }

    public record UpdateStatusRequest(@NotNull UserStatus status) {
    }

    @GetMapping("/me")
    @Operation(summary = "Perfil del usuario autenticado")
    public MeView me() {
        return service.me();
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lista usuarios (paginación keyset, sin datos personales)")
    public CursorPage<UserSummaryView> list(
            @Parameter(description = "Cursor devuelto por la página anterior") @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer size) {
        return service.list(after, CursorPage.clampSize(size));
    }

    @GetMapping("/{id}")
    @SensitiveRead(entity = "UserIdentity", entityId = "#id")
    @RequireScope(resource = "USER", id = "#id")
    @Operation(summary = "Detalle con identidad (auditado; requiere cabecera X-Access-Reason)")
    public UserDetailView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_ROLES_UPDATE", entity = "User", entityId = "#id")
    @Operation(summary = "Reemplaza los roles y programas de un usuario")
    public UserSummaryView updateRoles(@PathVariable UUID id, @Valid @RequestBody UpdateRolesRequest request) {
        return service.updateRoles(id, request.roles(), request.programIds());
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_STATUS_UPDATE", entity = "User", entityId = "#id")
    @Operation(summary = "Activa o desactiva un usuario")
    public UserSummaryView updateStatus(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest request) {
        return service.updateStatus(id, request.status());
    }
}

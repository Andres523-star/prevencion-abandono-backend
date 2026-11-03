package co.edu.retencion.users.api;

import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /**
     * @param email       correo con el que la persona entrará por Supabase
     * @param displayName nombre para mostrar
     * @param roles       roles iniciales
     * @param programIds  programas en su alcance (obligatorio para COORDINADOR y CONSEJERO)
     */
    public record CreateUserRequest(@NotBlank @Email @Size(max = 254) String email,
                                    @NotBlank @Size(max = 150) String displayName,
                                    @NotNull @Size(max = 6) Set<Role> roles,
                                    @NotNull @Size(max = 50) Set<UUID> programIds) {

        @Override
        public String toString() {
            return "CreateUserRequest[roles=" + roles + "]";
        }
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 150) String displayName) {
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
            @Parameter(description = "Filtra por rol, p. ej. DOCENTE") @RequestParam(required = false) Role role,
            @Parameter(description = "Cursor devuelto por la página anterior") @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer size) {
        return service.list(role, after, CursorPage.clampSize(size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_CREATE", entity = "User", entityId = "#result.id")
    @Operation(summary = "Crea un usuario antes de su primer ingreso (se vincula al entrar con ese correo)")
    public UserDetailView create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request.email(), request.displayName(), request.roles(), request.programIds());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_PROFILE_UPDATE", entity = "User", entityId = "#id")
    @Operation(summary = "Edita el nombre para mostrar")
    public UserDetailView updateProfile(@PathVariable UUID id, @Valid @RequestBody UpdateProfileRequest request) {
        return service.updateProfile(id, request.displayName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "USER_DELETE", entity = "User", entityId = "#id")
    @Operation(summary = "Borrado lógico del usuario (queda desactivado y oculto)")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
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

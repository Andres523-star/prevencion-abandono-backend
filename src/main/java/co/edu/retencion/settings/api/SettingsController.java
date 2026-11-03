package co.edu.retencion.settings.api;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.settings.application.SettingView;
import co.edu.retencion.settings.application.SettingsAdminService;

/** Administración de parámetros (solo ADMIN). Los cambios aplican sin redeploy. */
@RestController
@RequestMapping("/api/v1/settings")
@Validated
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Configuración")
public class SettingsController {

    private static final String KEY_PATTERN = "^[a-z0-9]+([.-][a-z0-9]+)*$";

    private final SettingsAdminService service;

    public SettingsController(SettingsAdminService service) {
        this.service = service;
    }

    /**
     * Cuerpo de actualización.
     *
     * @param value   nuevo valor JSON (mismo tipo que el actual)
     * @param version versión leída previamente (control optimista)
     */
    public record UpdateSettingRequest(@NotNull JsonNode value, @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    @Operation(summary = "Lista los parámetros configurables")
    public List<SettingView> list() {
        return service.list();
    }

    @GetMapping("/{key}")
    @Operation(summary = "Obtiene un parámetro")
    public SettingView get(@PathVariable @Pattern(regexp = KEY_PATTERN) String key) {
        return service.get(key);
    }

    @PutMapping("/{key}")
    @Audited(action = "SETTING_UPDATE", entity = "AppSetting", entityId = "#key")
    @Operation(summary = "Modifica un parámetro (requiere la versión actual)")
    public SettingView update(@PathVariable @Pattern(regexp = KEY_PATTERN) String key,
                              @Valid @RequestBody UpdateSettingRequest request) {
        return service.update(key, request.value(), request.version());
    }
}

package co.edu.retencion.risk.api;

import java.io.IOException;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.edu.retencion.audit.Audited;
import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.common.security.CurrentUser;
import co.edu.retencion.risk.application.DatasetExportService;
import co.edu.retencion.risk.application.MlModelRegistryService;
import co.edu.retencion.risk.application.MlModelRegistryService.ComparisonView;
import co.edu.retencion.risk.application.MlModelRegistryService.ModelCard;
import co.edu.retencion.risk.application.MlModelRegistryService.ModelView;

/**
 * Modelo predictivo propio (solo ADMIN, todo auditado): exportación del dataset seudonimizado,
 * registro de versiones ONNX, comparación contra el modelo activo y aprobación. La activación es por
 * configuración ({@code risk.ml.active-version}).
 */
@RestController
@RequestMapping("/api/v1/ml")
@Tag(name = "Modelo predictivo")
@PreAuthorize("hasRole('ADMIN')")
public class MlController {

    static final long MAX_MODEL_BYTES = 50L * 1024 * 1024;

    private final DatasetExportService datasets;
    private final MlModelRegistryService registry;

    public MlController(DatasetExportService datasets, MlModelRegistryService registry) {
        this.datasets = datasets;
        this.registry = registry;
    }

    public record ExportRequest(@NotBlank @Size(max = 300) String reason, List<@Size(max = 20) String> termCodes) {
    }

    public record ApproveRequest(boolean override, @Size(max = 300) String reason) {
    }

    @PostMapping("/dataset-exports")
    @Audited(action = "ML_DATASET_EXPORT", entity = "MlDataset")
    @Operation(summary = "Exporta el dataset seudonimizado de entrenamiento (CSV)")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ExportRequest request) {
        DatasetExportService.Export export = datasets.export(request.termCodes(), request.reason(),
                CurrentUser.require().userId());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("dataset-desercion.csv").build().toString())
                .header("X-Export-Id", export.id().toString())
                .header("X-Row-Count", String.valueOf(export.rows()))
                .header("X-Content-Sha256", export.sha256())
                .body(export.csv());
    }

    @PostMapping(value = "/models", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Audited(action = "ML_MODEL_REGISTER", entity = "MlModel", entityId = "#result.id")
    @Operation(summary = "Registra una versión del modelo (ficha JSON + artefacto ONNX)")
    public ModelView register(@RequestPart("card") ModelCard card, @RequestPart("model") MultipartFile model)
            throws IOException {
        if (model.getSize() > MAX_MODEL_BYTES) {
            throw DomainException.badRequest("El modelo supera el tamaño máximo");
        }
        return registry.register(card, model.getBytes());
    }

    @GetMapping("/models")
    @Operation(summary = "Versiones registradas y cuál está activa")
    public List<ModelView> models() {
        return registry.list();
    }

    @GetMapping("/models/{version}/comparison")
    @Operation(summary = "Compara la versión con el modelo activo (AUC, recall, calibración, brechas por grupo)")
    public ComparisonView compare(@PathVariable String version) {
        return registry.compare(version);
    }

    @PostMapping("/models/{version}/approve")
    @Audited(action = "ML_MODEL_APPROVE", entity = "MlModel", entityId = "#result.id")
    @Operation(summary = "Aprueba la versión para poder activarla en risk.ml.active-version")
    public ModelView approve(@PathVariable String version, @Valid @RequestBody(required = false) ApproveRequest request) {
        return registry.approve(version, request != null && request.override(), request == null ? null : request.reason());
    }
}

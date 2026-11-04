package co.edu.retencion.ai.domain;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import co.edu.retencion.common.error.DomainException;

/**
 * Recurso de apoyo de la universidad (tutorías, becas, apoyo psicológico...). Su id es un código
 * estable porque es lo que la IA debe citar en sus recomendaciones.
 */
@Entity
@Table(name = "support_resources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportResource {

    /** Categoría del recurso. */
    public enum Category {
        TUTORIA,
        MONITORIA,
        NIVELACION,
        BECA,
        APOYO_PSICOLOGICO,
        BIENESTAR,
        TRANSPORTE,
        ALIMENTACION,
        FLEXIBILIZACION,
        ASESORIA_FINANCIERA,
        CONSEJERIA,
        OTRO
    }

    /**
     * Reglas de elegibilidad evaluadas en el backend. Campos nulos = sin restricción.
     *
     * @param programs       códigos de programa admitidos
     * @param maxStratum     estrato máximo
     * @param minSemester    semestre mínimo
     * @param maxSemester    semestre máximo
     * @param transportModes medios de transporte admitidos
     * @param requiresWork   solo para quienes trabajan
     */
    public record Eligibility(List<String> programs, Integer maxStratum, Integer minSemester, Integer maxSemester,
                              List<String> transportModes, Boolean requiresWork) {

        /** Evalúa la elegibilidad; un dato desconocido (nulo) no excluye salvo que la regla lo exija. */
        public boolean admits(String programCode, int semester, Integer stratum, Boolean works, String transport) {
            if (programs != null && !programs.isEmpty() && !programs.contains(programCode)) {
                return false;
            }
            if (maxStratum != null && stratum != null && stratum > maxStratum) {
                return false;
            }
            if (minSemester != null && semester < minSemester) {
                return false;
            }
            if (maxSemester != null && semester > maxSemester) {
                return false;
            }
            if (transportModes != null && !transportModes.isEmpty()
                    && (transport == null || !transportModes.contains(transport))) {
                return false;
            }
            return requiresWork == null || !requiresWork || Boolean.TRUE.equals(works);
        }
    }

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String eligibility;

    @Column(name = "eligibility_text", nullable = false)
    private String eligibilityText;

    @Column(nullable = false)
    private String contact;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static SupportResource create(String id, String name, Category category, String description,
                                         String eligibilityJson, String eligibilityText, String contact, Instant now) {
        if (id == null || !id.matches("^[A-Z0-9]+(-[A-Z0-9]+)*$") || id.length() > 40) {
            throw DomainException.badRequest("El id del recurso debe ser un código en MAYÚSCULAS con guiones");
        }
        SupportResource r = new SupportResource();
        r.id = id;
        r.createdAt = now;
        r.update(name, category, description, eligibilityJson, eligibilityText, contact, true, now);
        return r;
    }

    public void update(String name, Category category, String description, String eligibilityJson,
                       String eligibilityText, String contact, boolean active, Instant now) {
        this.name = name.trim();
        this.category = category;
        this.description = description.trim();
        this.eligibility = eligibilityJson == null || eligibilityJson.isBlank() ? "{}" : eligibilityJson;
        this.eligibilityText = eligibilityText.trim();
        this.contact = contact.trim();
        this.active = active;
        this.updatedAt = now;
    }
}

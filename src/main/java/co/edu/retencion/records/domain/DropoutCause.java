package co.edu.retencion.records.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Causa del catálogo de riesgo / retiro (sembrado en la migración V3). */
@Entity
@Table(name = "dropout_causes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DropoutCause {

    /** Categoría de la causa. Las de SALUD solo las ve bienestar (PSICOLOGO) y ADMIN. */
    public enum Category {
        ACADEMICA,
        ECONOMICA,
        PERSONAL,
        SALUD,
        INSTITUCIONAL,
        OTRA
    }

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private boolean active;

    public DropoutCause(String code, String name, Category category) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.active = true;
    }

    public void update(String name, Category category, boolean active) {
        this.name = name;
        this.category = category;
        this.active = active;
    }
}

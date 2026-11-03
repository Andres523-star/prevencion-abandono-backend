package co.edu.retencion.settings.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Parámetro configurable. El valor es JSON (número, texto, booleano u objeto).
 * La versión permite control de concurrencia optimista al editar.
 */
@Entity
@Table(name = "app_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppSetting {

    @Id
    @Column(name = "setting_key")
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "setting_value", nullable = false)
    private String value;

    @Column(nullable = false)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    private long version;

    /** Cambia el valor registrando quién y cuándo. */
    public void change(String newJsonValue, UUID actor, Instant now) {
        this.value = newJsonValue;
        this.updatedBy = actor;
        this.updatedAt = now;
    }
}

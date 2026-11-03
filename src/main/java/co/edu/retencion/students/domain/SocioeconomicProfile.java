package co.edu.retencion.students.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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

import co.edu.retencion.common.error.DomainException;
import co.edu.retencion.students.TransportMode;

/**
 * Perfil socioeconómico, sin identificadores. Es información sensible para el análisis de riesgo
 * (usada con cuidado de sesgo en la Fase 4); solo lo ven ADMIN, CONSEJERO y PSICOLOGO.
 */
@Entity
@Table(name = "socioeconomic_profiles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocioeconomicProfile {

    @Id
    @Column(name = "student_id")
    private UUID studentId;

    private Integer stratum;

    private Boolean works;

    private Integer dependents;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_mode")
    private TransportMode transportMode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public static SocioeconomicProfile of(UUID studentId, Integer stratum, Boolean works, Integer dependents,
                                          TransportMode transport, Instant now) {
        SocioeconomicProfile p = new SocioeconomicProfile();
        p.studentId = studentId;
        p.update(stratum, works, dependents, transport, now);
        return p;
    }

    /** @return {@code true} si algo cambió */
    public boolean update(Integer stratum, Boolean works, Integer dependents, TransportMode transport, Instant now) {
        if (stratum != null && (stratum < 1 || stratum > 6)) {
            throw DomainException.badRequest("El estrato debe estar entre 1 y 6");
        }
        if (dependents != null && (dependents < 0 || dependents > 20)) {
            throw DomainException.badRequest("Las personas a cargo deben estar entre 0 y 20");
        }
        boolean changed = !Objects.equals(this.stratum, stratum) || !Objects.equals(this.works, works)
                || !Objects.equals(this.dependents, dependents) || this.transportMode != transport;
        if (changed || updatedAt == null) {
            this.stratum = stratum;
            this.works = works;
            this.dependents = dependents;
            this.transportMode = transport;
            this.updatedAt = now;
        }
        return changed;
    }
}

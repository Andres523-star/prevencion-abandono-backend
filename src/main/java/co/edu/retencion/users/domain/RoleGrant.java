package co.edu.retencion.users.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.security.Role;

/**
 * Asignación de un rol a un usuario, con trazabilidad de quién y cuándo lo otorgó.
 * La igualdad depende solo del rol (un usuario no puede tener el mismo rol dos veces).
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoleGrant {

    @Enumerated(EnumType.STRING)
    @Column(name = "role_code", nullable = false)
    private Role role;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by")
    private UUID grantedBy;

    RoleGrant(Role role, Instant grantedAt, UUID grantedBy) {
        this.role = role;
        this.grantedAt = grantedAt;
        this.grantedBy = grantedBy;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RoleGrant other && role == other.role;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(role);
    }
}

package co.edu.retencion.users.domain;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.SQLRestriction;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.common.id.UuidV7;
import co.edu.retencion.common.security.Role;
import co.edu.retencion.users.AuthType;
import co.edu.retencion.users.UserStatus;

/**
 * Usuario interno de la plataforma (agregado raíz del módulo users).
 *
 * <p>Correo y nombre se cifran en columna; {@code emailBlindIndex} permite buscar por correo.
 * Borrado lógico: las filas con {@code deleted_at} quedan fuera de todas las consultas.
 * Se construye solo con los métodos de fábrica estáticos, que garantizan un estado válido.
 */
@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private AuthType authType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "external_issuer")
    private String externalIssuer;

    @Column(name = "external_subject")
    private String externalSubject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_ciphertext")
    private String email;

    @Column(name = "email_blind_index")
    private String emailBlindIndex;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "display_name_ciphertext")
    private String displayName;

    /** Página de reservas de citas del docente (validada en la capa de aplicación). */
    @Column(name = "appointment_link")
    private String appointmentLink;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    private Set<RoleGrant> roleGrants = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_program_scopes", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "program_id", nullable = false)
    private Set<UUID> programIds = new HashSet<>();

    /** Primer ingreso con una cuenta de Supabase Auth. */
    public static User provisionExternal(String issuer, String subject, String email, String emailBlindIndex,
                                          String displayName, Instant now) {
        User user = new User();
        user.id = UuidV7.newId();
        user.authType = AuthType.SUPABASE;
        user.status = UserStatus.ACTIVE;
        user.externalIssuer = issuer;
        user.externalSubject = subject;
        user.email = email;
        user.emailBlindIndex = emailBlindIndex;
        user.displayName = displayName;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    /**
     * Usuario creado por un ADMIN o por importación (p. ej. un docente) antes de su primer ingreso.
     * Se vincula con su cuenta de Supabase la primera vez que entra con ese correo verificado.
     */
    public static User preProvision(String email, String emailBlindIndex, String displayName, Instant now) {
        return provisionExternal(null, null, email, emailBlindIndex, displayName, now);
    }

    /** Cuenta local de emergencia (sin correo institucional). */
    public static User createLocal(String label, Instant now) {
        User user = new User();
        user.id = UuidV7.newId();
        user.authType = AuthType.LOCAL;
        user.status = UserStatus.ACTIVE;
        user.displayName = label;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    /** Vincula una cuenta creada previamente (por correo) con su identidad externa. */
    public void linkExternal(String issuer, String subject, Instant now) {
        if (externalSubject != null && !externalSubject.equals(subject)) {
            throw new IllegalStateException("El usuario ya está vinculado a otra identidad");
        }
        this.externalIssuer = issuer;
        this.externalSubject = subject;
        this.updatedAt = now;
    }

    /** Actualiza correo y nombre si cambiaron en el directorio institucional. */
    public void refreshProfile(String email, String emailBlindIndex, String displayName, Instant now) {
        boolean changed = !emailBlindIndex.equals(this.emailBlindIndex)
                || (displayName != null && !displayName.equals(this.displayName));
        if (changed) {
            this.email = email;
            this.emailBlindIndex = emailBlindIndex;
            this.displayName = displayName;
            this.updatedAt = now;
        }
    }

    public void replaceRoles(Set<Role> roles, UUID grantedBy, Instant now) {
        roleGrants.removeIf(grant -> !roles.contains(grant.getRole()));
        Set<Role> current = roles();
        roles.stream().filter(r -> !current.contains(r)).forEach(r -> roleGrants.add(new RoleGrant(r, now, grantedBy)));
        this.updatedAt = now;
    }

    public void replacePrograms(Set<UUID> programs, Instant now) {
        programIds.clear();
        programIds.addAll(programs);
        this.updatedAt = now;
    }

    /** Agrega un rol si no lo tiene. @return {@code true} si se agregó */
    public boolean addRole(Role role, UUID grantedBy, Instant now) {
        if (roles().contains(role)) {
            return false;
        }
        roleGrants.add(new RoleGrant(role, now, grantedBy));
        updatedAt = now;
        return true;
    }

    /** @return {@code true} si el nombre cambió */
    public boolean rename(String newDisplayName, Instant now) {
        if (newDisplayName == null || newDisplayName.equals(displayName)) {
            return false;
        }
        this.displayName = newDisplayName;
        this.updatedAt = now;
        return true;
    }

    /**
     * Borrado lógico: la cuenta queda desactivada y fuera de todas las consultas, pero sus datos
     * permanecen para la auditoría (no hay borrado físico sin proceso formal de supresión).
     */
    public void softDelete(Instant now) {
        this.status = UserStatus.DISABLED;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public void changeStatus(UserStatus newStatus, Instant now) {
        this.status = newStatus;
        this.updatedAt = now;
    }

    public void changeAppointmentLink(String link, Instant now) {
        this.appointmentLink = link;
        this.updatedAt = now;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public Set<Role> roles() {
        return roleGrants.stream().map(RoleGrant::getRole)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
    }
}

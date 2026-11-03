package co.edu.retencion.students.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.crypto.EncryptedStringConverter;
import co.edu.retencion.students.DocumentType;

/**
 * Identidad del estudiante, en tabla separada y cifrada en columna (AES-256-GCM). El documento y
 * el correo tienen blind index para buscar sin descifrar. Nunca sale en DTO públicos.
 */
@Entity
@Table(name = "student_identities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentIdentity {

    @Id
    @Column(name = "student_id")
    private UUID studentId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "full_name_ciphertext", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false)
    private DocumentType documentType;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "document_ciphertext", nullable = false)
    private String documentNumber;

    @Column(name = "document_blind_index", nullable = false, unique = true)
    private String documentBlindIndex;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "email_ciphertext")
    private String email;

    @Column(name = "email_blind_index")
    private String emailBlindIndex;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "phone_ciphertext")
    private String phone;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /**
     * Datos de identidad ya normalizados, con sus índices calculados.
     *
     * @param fullName           nombre completo
     * @param documentType       tipo de documento
     * @param documentNumber     número de documento
     * @param documentBlindIndex HMAC del documento
     * @param email              correo (puede ser {@code null})
     * @param emailBlindIndex    HMAC del correo (puede ser {@code null})
     * @param phone              teléfono (puede ser {@code null})
     */
    public record Data(String fullName, DocumentType documentType, String documentNumber, String documentBlindIndex,
                       String email, String emailBlindIndex, String phone) {

        @Override
        public String toString() {
            return "StudentIdentity.Data[***]";
        }
    }

    public static StudentIdentity of(UUID studentId, Data data, Instant now) {
        StudentIdentity identity = new StudentIdentity();
        identity.studentId = studentId;
        identity.apply(data, now);
        return identity;
    }

    /** @return {@code true} si algo cambió */
    public boolean update(Data data, Instant now) {
        boolean changed = !Objects.equals(fullName, data.fullName()) || documentType != data.documentType()
                || !Objects.equals(documentBlindIndex, data.documentBlindIndex())
                || !Objects.equals(emailBlindIndex, data.emailBlindIndex()) || !Objects.equals(phone, data.phone());
        if (changed) {
            apply(data, now);
        }
        return changed;
    }

    private void apply(Data data, Instant now) {
        this.fullName = data.fullName();
        this.documentType = data.documentType();
        this.documentNumber = data.documentNumber();
        this.documentBlindIndex = data.documentBlindIndex();
        this.email = data.email();
        this.emailBlindIndex = data.emailBlindIndex();
        this.phone = data.phone();
        this.updatedAt = now;
    }

    @Override
    public String toString() {
        return "StudentIdentity[studentId=" + studentId + "]";
    }
}

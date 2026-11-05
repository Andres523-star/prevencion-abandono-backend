package co.edu.retencion.chat.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Participante de una conversación, con su última lectura (no leídos y confirmación de lectura). */
@Entity
@Table(name = "chat_participants")
@IdClass(Participant.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Participant {

    /** Rol dentro del chat. */
    public enum Role {
        DOCENTE,
        ESTUDIANTE,
        CONSEJERO,
        PSICOLOGO
    }

    /** Clave compuesta. */
    public record Key(UUID conversationId, UUID userId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "conversation_id")
    private UUID conversationId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    public static Participant of(UUID conversationId, UUID userId, Role role, Instant now) {
        Participant p = new Participant();
        p.conversationId = conversationId;
        p.userId = userId;
        p.role = role;
        p.joinedAt = now;
        return p;
    }

    public void read(Instant now) {
        this.lastReadAt = now;
    }
}

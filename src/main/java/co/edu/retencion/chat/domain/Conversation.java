package co.edu.retencion.chat.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import co.edu.retencion.common.id.UuidV7;

/** Conversación docente-estudiante (una sola por par, garantizada por {@code pair_key}). */
@Entity
@Table(name = "chat_conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String kind;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "context_group_id")
    private UUID contextGroupId;

    @Column(name = "pair_key", nullable = false, unique = true)
    private String pairKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    public static String pairKey(UUID teacherUserId, UUID studentId) {
        return teacherUserId + ":" + studentId;
    }

    public static Conversation teacherStudent(UUID teacherUserId, UUID studentId, UUID contextGroupId, Instant now) {
        Conversation c = new Conversation();
        c.id = UuidV7.newId();
        c.kind = "DOCENTE_ESTUDIANTE";
        c.studentId = studentId;
        c.ownerUserId = teacherUserId;
        c.contextGroupId = contextGroupId;
        c.pairKey = pairKey(teacherUserId, studentId);
        c.createdAt = now;
        return c;
    }

    public void messageAt(Instant now) {
        this.lastMessageAt = now;
    }
}

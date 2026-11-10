package co.edu.retencion.notifications.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Notificaciones. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    @Query("""
            select n from Notification n where n.recipientId = :recipient and n.expiresAt > :now
              and (:fromStart = true or n.id < :before)
            order by n.id desc
            """)
    List<Notification> page(UUID recipient, Instant now, boolean fromStart, UUID before, Limit limit);

    @Query("select count(n) from Notification n where n.recipientId = :recipient and n.readAt is null and n.expiresAt > :now")
    long unread(UUID recipient, Instant now);

    @Query("""
            select count(n) > 0 from Notification n
            where n.recipientId = :recipient and n.type = :type and n.studentId = :studentId
              and n.reasonHash = :hash and coalesce(n.riskLevel, '') = coalesce(:level, '') and n.createdAt > :since
            """)
    boolean duplicate(UUID recipient, Notification.Type type, UUID studentId, String hash, String level, Instant since);

    boolean existsByRecipientIdAndTypeAndReferenceIdAndReadAtIsNull(UUID recipient, Notification.Type type, UUID reference);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.recipientId = :recipient and n.readAt is null")
    int readAll(UUID recipient, Instant now);

    @Modifying
    @Query("delete from Notification n where n.recipientId = :user or n.studentId = :student")
    int deleteForSubject(UUID user, UUID student);

    @Modifying
    @Query("delete from Notification n where n.expiresAt <= :now")
    int purgeExpired(Instant now);
}

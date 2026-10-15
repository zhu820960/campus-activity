package edu.demo.campusactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity
@Table(name = "user_messages", indexes = {
    @Index(name = "idx_message_recipient_created", columnList = "recipient_id,created_at"),
    @Index(name = "idx_message_identity_created", columnList = "identity_id,created_at")
}, uniqueConstraints = @UniqueConstraint(name = "uk_message_event_key", columnNames = "event_key"))
class UserMessage {
    enum Kind { REGISTRATION_SUCCESS, REVIEW_APPROVED, REVIEW_REJECTED,
        ORGANIZER_GRANTED, ORGANIZER_REVOKED, ACTIVITY_CANCELLED, ACTIVITY_UPDATED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    UserAccount recipient;
    @Column(name = "identity_id")
    Long identityId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id")
    Activity activity;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    Kind kind;
    @Column(nullable = false, length = 160)
    String title;
    @Column(nullable = false, length = 500)
    String body;
    @Column(name = "event_key", nullable = false, length = 120)
    String eventKey;
    @Column(name = "created_at", nullable = false)
    Instant createdAt = Instant.now();
    @Column(name = "read_at")
    Instant readAt;

    protected UserMessage() {}

    UserMessage(UserAccount recipient, Long identityId, Activity activity, Kind kind, String title,
                String body, String eventKey) {
        this.recipient = recipient;
        this.identityId = identityId;
        this.activity = activity;
        this.kind = kind;
        this.title = title;
        this.body = body;
        this.eventKey = eventKey;
    }
}

interface UserMessageRepository extends JpaRepository<UserMessage, Long> {
    List<UserMessage> findTop100ByRecipientIdAndIdentityIdOrderByCreatedAtDesc(Long recipientId, Long identityId);
    java.util.Optional<UserMessage> findByIdAndRecipientIdAndIdentityId(Long id, Long recipientId, Long identityId);
    long countByRecipientIdAndIdentityIdAndReadAtIsNull(Long recipientId, Long identityId);
    long countByRecipientIdAndIdentityIdIsNullAndEventKeyStartingWithAndReadAtIsNull(Long recipientId, String prefix);
    boolean existsByEventKey(String eventKey);
    List<UserMessage> findTop100ByIdentityIdOrderByCreatedAtDesc(Long identityId);
    long countByIdentityIdAndReadAtIsNull(Long identityId);
    java.util.Optional<UserMessage> findByIdAndIdentityId(Long id, Long identityId);
}

package edu.demo.campusactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "activity_audits")
class ActivityAudit {
    enum Action { CREATED, EDITED, DIRECT_PUBLISHED, REVIEW_APPROVED, REVIEW_REJECTED,
        QR_ISSUED, FEEDBACK_HIDDEN }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    Activity activity;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    UserAccount actor;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    Action action;
    @Column(nullable = false)
    Instant createdAt = Instant.now();
    @Column(length = 500)
    String detail;

    protected ActivityAudit() {}

    ActivityAudit(Activity activity, UserAccount actor, Action action) {
        this.activity = activity;
        this.actor = actor;
        this.action = action;
    }

    ActivityAudit(Activity activity, UserAccount actor, Action action, String detail) {
        this(activity, actor, action);
        this.detail = detail;
    }
}

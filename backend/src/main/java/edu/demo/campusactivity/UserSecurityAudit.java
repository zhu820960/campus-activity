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
@Table(name = "user_security_audits")
class UserSecurityAudit {
    enum Action { ORGANIZER_GRANTED, ORGANIZER_REVOKED, WECHAT_UNBOUND, BINDING_APPEAL_APPROVED, USER_DELETED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    UserAccount actor;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false)
    UserAccount target;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    Action action;
    @Column(nullable = false)
    Instant createdAt = Instant.now();

    protected UserSecurityAudit() {}

    UserSecurityAudit(UserAccount actor, UserAccount target, Action action) {
        this.actor = actor;
        this.target = target;
        this.action = action;
    }
}

record UserSecurityAuditView(Long actorId, String actorName, Long targetId, String targetName,
                             UserSecurityAudit.Action action, Instant createdAt) {
    static UserSecurityAuditView of(UserSecurityAudit audit) {
        return new UserSecurityAuditView(audit.actor.getId(), audit.actor.getName(), audit.target.getId(),
            audit.target.getName(), audit.action, audit.createdAt);
    }
}

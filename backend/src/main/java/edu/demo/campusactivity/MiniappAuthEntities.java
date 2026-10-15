package edu.demo.campusactivity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.LockModeType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Entity
@Table(name = "wechat_identities", uniqueConstraints =
    @UniqueConstraint(name = "uk_wechat_identity_app_openid", columnNames = {"app_id", "open_id"}))
class WechatIdentity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "app_id", nullable = false, length = 32)
    String appId;
    @Column(name = "open_id", nullable = false, length = 128)
    String openId;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected WechatIdentity() {}

    WechatIdentity(String appId, String openId) {
        this.appId = appId;
        this.openId = openId;
        this.createdAt = Instant.now();
    }

    Long getId() { return id; }
    String getAppId() { return appId; }
    String getOpenId() { return openId; }
}

@Entity
@Table(name = "wechat_student_bindings")
class WechatStudentBinding {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "app_id", nullable = false, length = 32)
    String appId;
    @Column(name = "open_id", nullable = false, length = 128)
    String openId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "identity_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_binding_identity"))
    WechatIdentity identity;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_wechat_binding_student"))
    UserAccount student;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "unbound_at")
    Instant unboundAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unbound_by", foreignKey = @ForeignKey(name = "fk_binding_unbound_by"))
    UserAccount unboundBy;
    @Column(name = "unbound_reason", length = 500)
    String unboundReason;

    protected WechatStudentBinding() {}

    WechatStudentBinding(WechatIdentity identity, UserAccount student) {
        this.identity = identity;
        this.appId = identity.getAppId();
        this.openId = identity.getOpenId();
        this.student = student;
        this.createdAt = Instant.now();
    }

    String getAppId() { return appId; }
    Long getId() { return id; }
    WechatIdentity getIdentity() { return identity; }
    UserAccount getStudent() { return student; }
    Instant getUnboundAt() { return unboundAt; }

    void unbind(UserAccount actor, String reason, Instant now) {
        this.unboundAt = now;
        this.unboundBy = actor;
        this.unboundReason = reason;
    }
}

@Entity
@Table(name = "miniapp_access_tokens", indexes = {
    @Index(name = "idx_miniapp_access_token_expires", columnList = "expires_at"),
    @Index(name = "idx_miniapp_access_token_binding", columnList = "binding_id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_miniapp_access_token_hash", columnNames = "token_hash"))
class MiniappAccessToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "binding_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_miniapp_access_token_binding"))
    WechatStudentBinding binding;
    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    String tokenHash;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;
    @Column(name = "revoked_at")
    Instant revokedAt;

    protected MiniappAccessToken() {}

    MiniappAccessToken(WechatStudentBinding binding, String tokenHash, Instant now) {
        this.binding = binding;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = now.plusSeconds(7 * 24 * 60 * 60);
    }

    WechatStudentBinding getBinding() { return binding; }
}

interface WechatStudentBindingRepository extends JpaRepository<WechatStudentBinding, Long> {
    Optional<WechatStudentBinding> findByAppIdAndOpenIdAndUnboundAtIsNull(String appId, String openId);
    Optional<WechatStudentBinding> findByAppIdAndStudentIdAndUnboundAtIsNull(String appId, Long studentId);
    @Query("select b.identity.id from WechatStudentBinding b where b.appId = :appId "
        + "and b.student.id = :studentId and b.unboundAt is null")
    Optional<Long> findActiveIdentityId(@Param("appId") String appId, @Param("studentId") Long studentId);
    boolean existsByAppIdAndOpenIdAndUnboundAtIsNotNull(String appId, String openId);
    java.util.List<WechatStudentBinding> findByIdentityId(Long identityId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from WechatStudentBinding b where b.appId = :appId and b.student.id = :studentId "
        + "and b.unboundAt is null")
    Optional<WechatStudentBinding> lockActiveByStudent(@Param("appId") String appId,
        @Param("studentId") Long studentId);
}

interface WechatIdentityRepository extends JpaRepository<WechatIdentity, Long> {
    Optional<WechatIdentity> findByAppIdAndOpenId(String appId, String openId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WechatIdentity i where i.id = :id")
    Optional<WechatIdentity> lockById(@Param("id") Long id);
}

interface MiniappAccessTokenRepository extends JpaRepository<MiniappAccessToken, Long> {
    Optional<MiniappAccessToken> findByTokenHash(String tokenHash);
    @Modifying
    @Query("update MiniappAccessToken t set t.revokedAt = :now where t.binding.id = :bindingId "
        + "and t.revokedAt is null")
    int revokeByBinding(@Param("bindingId") Long bindingId, @Param("now") Instant now);
}

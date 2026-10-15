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
import jakarta.persistence.LockModeType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Entity
@Table(name = "appeal_challenges")
class AppealChallenge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "identity_id", nullable = false)
    WechatIdentity identity;
    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    String tokenHash;
    @Column(name = "evidence_key", length = 120)
    String evidenceKey;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;
    @Column(name = "evidence_uploaded_at")
    Instant evidenceUploadedAt;
    @Column(name = "consumed_at")
    Instant consumedAt;

    protected AppealChallenge() {}

    AppealChallenge(WechatIdentity identity, String tokenHash, Instant now) {
        this.identity = identity;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = now.plusSeconds(10 * 60);
    }
}

@Entity
@Table(name = "binding_appeals")
class BindingAppeal {
    enum Type { STUDENT_NUMBER_OCCUPIED, WRONG_STUDENT_NUMBER }
    enum Status { PENDING, APPROVED, REJECTED, DISPUTED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_identity_id", nullable = false)
    WechatIdentity applicantIdentity;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_binding_id")
    WechatStudentBinding currentBinding;
    @Enumerated(EnumType.STRING)
    @Column(name = "appeal_type", nullable = false, length = 32)
    Type appealType;
    @Column(name = "proposed_student_number", nullable = false, length = 14)
    String proposedStudentNumber;
    @Column(name = "reason", nullable = false, length = 1000)
    String reason;
    @Column(name = "evidence_key", nullable = false, length = 120)
    String evidenceKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    Status status;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "reviewed_at")
    Instant reviewedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    UserAccount reviewer;
    @Column(name = "review_note", length = 1000)
    String reviewNote;
    @Column(name = "evidence_deleted_at")
    Instant evidenceDeletedAt;

    protected BindingAppeal() {}

    BindingAppeal(WechatIdentity identity, WechatStudentBinding binding, Type type, String number,
                  String reason, String evidenceKey, Instant now) {
        this.applicantIdentity = identity;
        this.currentBinding = binding;
        this.appealType = type;
        this.proposedStudentNumber = number;
        this.reason = reason;
        this.evidenceKey = evidenceKey;
        this.status = Status.PENDING;
        this.createdAt = now;
    }
}

@Entity
@Table(name = "participation_attribution_reviews")
class ParticipationAttributionReview {
    enum Decision { TRANSFER, KEEP, DISPUTED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appeal_id", nullable = false)
    BindingAppeal appeal;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", nullable = false)
    Registration registration;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checkin_id")
    Checkin checkin;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_student_id", nullable = false)
    UserAccount sourceStudent;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_student_id", nullable = false)
    UserAccount targetStudent;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    Decision decision;
    @Column(nullable = false, length = 1000)
    String reason;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    UserAccount reviewer;
    @Column(name = "reviewed_at", nullable = false)
    Instant reviewedAt;

    protected ParticipationAttributionReview() {}

    ParticipationAttributionReview(BindingAppeal appeal, Registration registration, Checkin checkin,
                                   UserAccount source, UserAccount target, Decision decision,
                                   String reason, UserAccount reviewer, Instant now) {
        this.appeal = appeal;
        this.registration = registration;
        this.checkin = checkin;
        this.sourceStudent = source;
        this.targetStudent = target;
        this.decision = decision;
        this.reason = reason;
        this.reviewer = reviewer;
        this.reviewedAt = now;
    }
}

interface ParticipationAttributionReviewRepository extends JpaRepository<ParticipationAttributionReview, Long> {
    List<ParticipationAttributionReview> findByAppealIdOrderByRegistrationId(Long appealId);
}

interface AppealChallengeRepository extends JpaRepository<AppealChallenge, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AppealChallenge c where c.tokenHash = :tokenHash")
    Optional<AppealChallenge> lockByTokenHash(@Param("tokenHash") String tokenHash);
    long countByIdentityIdAndCreatedAtAfter(Long identityId, Instant after);
    List<AppealChallenge> findByExpiresAtBeforeAndConsumedAtIsNullAndEvidenceKeyIsNotNull(Instant before);
}

interface BindingAppealRepository extends JpaRepository<BindingAppeal, Long>, JpaSpecificationExecutor<BindingAppeal> {
    boolean existsByApplicantIdentityIdAndStatus(Long identityId, BindingAppeal.Status status);
    List<BindingAppeal> findByStatusInOrderByCreatedAtAsc(List<BindingAppeal.Status> statuses);
    List<BindingAppeal> findByReviewedAtBeforeAndEvidenceDeletedAtIsNull(Instant before);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from BindingAppeal a where a.id = :id")
    Optional<BindingAppeal> lockById(@Param("id") Long id);
    Optional<BindingAppeal> findTopByApplicantIdentityIdOrderByCreatedAtDesc(Long identityId);
}

@Service
class PrivateAppealEvidenceStore {
    private final Path directory;

    PrivateAppealEvidenceStore(@Value("${app.appeals.directory:./uploads/private-appeals}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    String save(MultipartFile upload) {
        SafeImageCodec.Encoded image = SafeImageCodec.encode(upload, 3L * 1024 * 1024);
        try {
            Files.createDirectories(directory);
            String key = UUID.randomUUID().toString().replace("-", "") + "." + image.extension();
            Path temporary = Files.createTempFile(directory, "appeal-", ".tmp");
            try {
                Files.write(temporary, image.bytes());
                Files.move(temporary, directory.resolve(key), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return key;
        } catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "证明材料保存失败");
        }
    }

    Resource read(String key) {
        if (key == null || !key.matches("[0-9a-f]{32}\\.(jpg|png)")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "证明材料不存在");
        }
        Path file = directory.resolve(key).normalize();
        if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "证明材料不存在");
        }
        return new FileSystemResource(file);
    }

    void delete(String key) {
        if (key == null || !key.matches("[0-9a-f]{32}\\.(jpg|png)")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "证明材料文件名无效");
        }
        try {
            Files.deleteIfExists(directory.resolve(key));
        } catch (IOException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "证明材料清理失败");
        }
    }
}

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.appeals.enabled", havingValue = "true")
class BindingAppealCleanupSchedule {}

@Service
class BindingAppealEvidenceCleanup {
    private final AppealChallengeRepository challenges;
    private final BindingAppealRepository appeals;
    private final PrivateAppealEvidenceStore evidence;

    BindingAppealEvidenceCleanup(AppealChallengeRepository challenges, BindingAppealRepository appeals,
                                 PrivateAppealEvidenceStore evidence) {
        this.challenges = challenges;
        this.appeals = appeals;
        this.evidence = evidence;
    }

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    void removeExpiredEvidence() {
        Instant now = Instant.now();
        for (AppealChallenge challenge : challenges
            .findByExpiresAtBeforeAndConsumedAtIsNullAndEvidenceKeyIsNotNull(now)) {
            evidence.delete(challenge.evidenceKey);
            challenge.evidenceKey = null;
        }
        for (BindingAppeal appeal : appeals
            .findByReviewedAtBeforeAndEvidenceDeletedAtIsNull(now.minusSeconds(30L * 24 * 3600))) {
            if (appeal.status != BindingAppeal.Status.APPROVED
                && appeal.status != BindingAppeal.Status.REJECTED) continue;
            evidence.delete(appeal.evidenceKey);
            appeal.evidenceDeletedAt = now;
        }
    }
}

@Service
class BindingAppealService {
    enum ReviewQueue { PENDING, DISPUTED, REVIEWED }
    record ChallengeResult(String token, Instant expiresAt) {
        @Override public String toString() { return "ChallengeResult[token=REDACTED]"; }
    }
    record AppealView(Long id, BindingAppeal.Type type, String proposedStudentNumber, String reason,
                      BindingAppeal.Status status, Instant createdAt, Long currentStudentId) {
        static AppealView of(BindingAppeal appeal) {
            return new AppealView(appeal.id, appeal.appealType, appeal.proposedStudentNumber,
                appeal.reason, appeal.status, appeal.createdAt,
                appeal.currentBinding == null ? null : appeal.currentBinding.getStudent().getId());
        }
    }
    record StatusView(Long id, BindingAppeal.Type type, String proposedStudentNumber,
                      BindingAppeal.Status status, Instant createdAt, Instant reviewedAt, String reviewNote) {
        static StatusView of(BindingAppeal appeal) {
            return new StatusView(appeal.id, appeal.appealType, appeal.proposedStudentNumber,
                appeal.status, appeal.createdAt, appeal.reviewedAt, appeal.reviewNote);
        }
    }
    record ReviewView(Long id, BindingAppeal.Type type, String proposedStudentNumber, String reason,
                      BindingAppeal.Status status, Instant createdAt, Instant reviewedAt,
                      String reviewerName, String reviewNote, boolean evidenceAvailable) {
        static ReviewView of(BindingAppeal appeal) {
            return new ReviewView(appeal.id, appeal.appealType, appeal.proposedStudentNumber,
                appeal.reason, appeal.status, appeal.createdAt, appeal.reviewedAt,
                appeal.reviewer == null ? null : appeal.reviewer.getName(), appeal.reviewNote,
                appeal.evidenceDeletedAt == null);
        }
    }
    record ImpactRow(Long registrationId, Long activityId, String activityTitle, Long sourceStudentId,
                     Long checkinId, String source, boolean targetHasRegistration,
                     boolean sourceCheckinConflict) {}
    record OwnedActivity(Long id, String title, Activity.Status status) {}
    record ImpactAccount(Long studentId, String name, String studentNumber, boolean organizer,
                         boolean webCredentialsActive, long activityCount, boolean boundToOtherIdentity,
                         List<OwnedActivity> ownedActivities) {}
    record ImpactView(Long appealId, Long targetStudentId, List<ImpactAccount> accounts,
                      boolean requiresActivityOwnershipReview, List<ImpactRow> rows) {}
    record AttributionReviewView(Long registrationId, Long checkinId, Long sourceStudentId,
                                 Long targetStudentId, ParticipationAttributionReview.Decision decision,
                                 String reason, String reviewerName, Instant reviewedAt) {
        static AttributionReviewView of(ParticipationAttributionReview review) {
            return new AttributionReviewView(review.registration.getId(),
                review.checkin == null ? null : review.checkin.getId(), review.sourceStudent.getId(),
                review.targetStudent.getId(), review.decision, review.reason, review.reviewer.getName(),
                review.reviewedAt);
        }
    }
    private record AttendanceKey(Long activityId, Long studentId) {}

    private final String appId;
    private final boolean enabled;
    private final WechatIdentityRepository identities;
    private final WechatStudentBindingRepository bindings;
    private final AppealChallengeRepository challenges;
    private final BindingAppealRepository appeals;
    private final PrivateAppealEvidenceStore evidence;
    private final UserRepository users;
    private final ActivityRepository activities;
    private final RegistrationRepository registrations;
    private final CheckinRepository checkins;
    private final MiniappAccessTokenRepository tokens;
    private final ParticipationAttributionReviewRepository attributionReviews;
    private final UserSecurityAuditRepository securityAudits;
    private final UserMessageService notifications;
    private final SecureRandom random = new SecureRandom();

    BindingAppealService(@Value("${app.wechat.app-id:}") String appId,
                         @Value("${app.appeals.enabled:false}") boolean enabled,
                         WechatIdentityRepository identities, WechatStudentBindingRepository bindings,
                         AppealChallengeRepository challenges, BindingAppealRepository appeals,
                         PrivateAppealEvidenceStore evidence, UserRepository users, ActivityRepository activities,
                         RegistrationRepository registrations, CheckinRepository checkins,
                         MiniappAccessTokenRepository tokens,
                         ParticipationAttributionReviewRepository attributionReviews,
                         UserSecurityAuditRepository securityAudits, UserMessageService notifications) {
        this.appId = appId;
        this.enabled = enabled;
        this.identities = identities;
        this.bindings = bindings;
        this.challenges = challenges;
        this.appeals = appeals;
        this.evidence = evidence;
        this.users = users;
        this.activities = activities;
        this.registrations = registrations;
        this.checkins = checkins;
        this.tokens = tokens;
        this.attributionReviews = attributionReviews;
        this.securityAudits = securityAudits;
        this.notifications = notifications;
    }

    @Transactional
    ChallengeResult challenge(String openId) {
        requireEnabled();
        if (appId.isBlank() || openId == null || openId.isBlank() || openId.length() > 128) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "微信身份无效");
        }
        WechatIdentity identity = identities.findByAppIdAndOpenId(appId, openId)
            .orElseGet(() -> identities.saveAndFlush(new WechatIdentity(appId, openId)));
        Instant now = Instant.now();
        if (hasOpenAppeal(identity.id)) {
            throw new ApiException(HttpStatus.CONFLICT, "已有待处理申诉，请等待管理员审核");
        }
        if (challenges.countByIdentityIdAndCreatedAtAfter(identity.id, now.minusSeconds(3600)) >= 5) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "申诉请求过于频繁，请稍后再试");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AppealChallenge saved = challenges.save(new AppealChallenge(identity, hash(token), now));
        return new ChallengeResult(token, saved.expiresAt);
    }

    @Transactional
    void upload(String rawToken, MultipartFile upload) {
        AppealChallenge challenge = validChallenge(rawToken);
        if (challenge.evidenceKey != null) {
            throw new ApiException(HttpStatus.CONFLICT, "证明材料已上传，请提交申诉或重新开始");
        }
        challenge.evidenceKey = evidence.save(upload);
        challenge.evidenceUploadedAt = Instant.now();
    }

    @Transactional
    AppealView submit(String rawToken, BindingAppeal.Type type, String proposedNumber, String reason) {
        AppealChallenge challenge = validChallenge(rawToken);
        if (type == null || proposedNumber == null || !proposedNumber.matches("[0-9]{14}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写14位目标学号和申诉类型");
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.isBlank() || trimmedReason.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写申诉原因（1000字以内）");
        }
        if (challenge.evidenceKey == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请先上传学生证照片");
        }
        WechatIdentity identity = identities.lockById(challenge.identity.getId())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "微信身份无效"));
        if (hasOpenAppeal(identity.id)) {
            throw new ApiException(HttpStatus.CONFLICT, "已有待处理申诉，请等待管理员审核");
        }
        WechatStudentBinding active = bindings.findByAppIdAndOpenIdAndUnboundAtIsNull(appId, identity.getOpenId())
            .orElse(null);
        if (type == BindingAppeal.Type.WRONG_STUDENT_NUMBER && active == null) {
            throw new ApiException(HttpStatus.CONFLICT, "当前微信未绑定学号，请选择学号被占用申诉");
        }
        if (type == BindingAppeal.Type.STUDENT_NUMBER_OCCUPIED && active != null) {
            throw new ApiException(HttpStatus.CONFLICT, "当前微信已有绑定，请选择绑错学号申诉");
        }
        if (active != null && proposedNumber.equals(active.getStudent().getStudentNumber())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "目标学号不能与当前学号相同");
        }
        BindingAppeal appeal = appeals.saveAndFlush(new BindingAppeal(identity, active, type,
            proposedNumber, trimmedReason, challenge.evidenceKey, Instant.now()));
        challenge.consumedAt = Instant.now();
        return AppealView.of(appeal);
    }

    @Transactional(readOnly = true)
    List<AppealView> pending() {
        requireEnabled();
        return appeals.findByStatusInOrderByCreatedAtAsc(List.of(
                BindingAppeal.Status.PENDING, BindingAppeal.Status.DISPUTED))
            .stream().map(AppealView::of).toList();
    }

    @Transactional(readOnly = true)
    ReviewView review(Long id) {
        requireEnabled();
        return ReviewView.of(appeals.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "申诉不存在")));
    }

    @Transactional(readOnly = true)
    AuditPage<ReviewView> search(ReviewQueue queue, String studentNumber, int page, int size) {
        requireEnabled();
        String number = studentNumber == null ? "" : studentNumber.trim();
        if (queue == null || !number.matches("[0-9]{0,14}") || page < 0 || page > 10000
            || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "申诉筛选条件无效");
        }
        Specification<BindingAppeal> spec = (root, query, cb) -> cb.and(
            queue == ReviewQueue.PENDING ? cb.equal(root.get("status"), BindingAppeal.Status.PENDING)
                : queue == ReviewQueue.DISPUTED ? cb.equal(root.get("status"), BindingAppeal.Status.DISPUTED)
                : root.get("status").in(BindingAppeal.Status.APPROVED, BindingAppeal.Status.REJECTED),
            number.isEmpty() ? cb.conjunction() : cb.like(root.get("proposedStudentNumber"), "%" + number + "%"));
        Sort sort = queue == ReviewQueue.PENDING ? Sort.by("createdAt", "id")
            : Sort.by(Sort.Direction.DESC, "reviewedAt", "id");
        return AuditPage.of(appeals.findAll(spec, PageRequest.of(page, size, sort)).map(ReviewView::of));
    }

    @Transactional(readOnly = true)
    StatusView latestForWechat(String openId) {
        requireEnabled();
        WechatIdentity identity = identities.findByAppIdAndOpenId(appId, openId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "暂无申诉记录"));
        return appeals.findTopByApplicantIdentityIdOrderByCreatedAtDesc(identity.id)
            .map(StatusView::of)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "暂无申诉记录"));
    }

    @Transactional(readOnly = true)
    ImpactView impact(Long id) {
        requireEnabled();
        BindingAppeal appeal = appeals.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "申诉不存在"));
        Long identityId = appeal.applicantIdentity.getId();
        Long targetStudentId = users.findByStudentNumber(appeal.proposedStudentNumber)
            .map(UserAccount::getId).orElse(null);
        Set<Long> historicalStudents = bindings.findByIdentityId(identityId).stream()
            .map(binding -> binding.getStudent().getId()).collect(Collectors.toSet());
        Set<Long> involvedAccounts = new java.util.HashSet<>(historicalStudents);
        if (targetStudentId != null) involvedAccounts.add(targetStudentId);
        List<ImpactAccount> accounts = users.findAllById(involvedAccounts).stream().map(account -> {
            long activityCount = activities.countByOrganizerId(account.getId());
            List<OwnedActivity> owned = activities.findByOrganizerIdOrderByCreatedAtDesc(
                account.getId(), PageRequest.of(0, 50)).stream()
                .map(activity -> new OwnedActivity(activity.getId(), activity.getTitle(), activity.getStatus()))
                .toList();
            boolean boundToOther = bindings.findByAppIdAndStudentIdAndUnboundAtIsNull(appId, account.getId())
                .map(binding -> !identityId.equals(binding.getIdentity().getId())).orElse(false);
            return new ImpactAccount(account.getId(), account.getName(), account.getStudentNumber(),
                account.getRoles().contains(UserAccount.Role.ORGANIZER), account.passwordHash != null,
                activityCount, boundToOther, owned);
        }).sorted(java.util.Comparator.comparing(ImpactAccount::studentId)).toList();
        boolean requiresOwnershipReview = accounts.stream().anyMatch(account -> account.activityCount() > 0);
        if (historicalStudents.isEmpty()) {
            return new ImpactView(id, targetStudentId, accounts, requiresOwnershipReview, List.of());
        }
        List<Registration> candidates = registrations.findByStudentIdInOrderByRegisteredAtDesc(historicalStudents)
            .stream().filter(row -> row.identityId == null || identityId.equals(row.identityId)).toList();
        if (candidates.isEmpty()) {
            return new ImpactView(id, targetStudentId, accounts, requiresOwnershipReview, List.of());
        }
        Set<Long> targetActivities = targetStudentId == null ? Set.of()
            : registrations.findByActivityIdInAndStudentId(candidates.stream()
                .map(row -> row.activity.getId()).collect(Collectors.toSet()), targetStudentId)
                .stream().map(row -> row.activity.getId()).collect(Collectors.toSet());
        java.util.Map<AttendanceKey, Long> registrationByAttendance = new java.util.HashMap<>();
        java.util.Map<Long, Registration> registrationById = new java.util.HashMap<>();
        for (Registration row : candidates) {
            registrationByAttendance.put(new AttendanceKey(row.activity.getId(), row.student.getId()), row.id);
            registrationById.put(row.id, row);
        }
        java.util.Map<Long, Checkin> checkinsByRegistration = new java.util.HashMap<>();
        Set<Long> mismatchedCheckins = new java.util.HashSet<>();
        for (Checkin checkin : checkins.findForRegistrations(candidates.stream().map(row -> row.id).toList())) {
            Long registrationId = registrationByAttendance.get(
                new AttendanceKey(checkin.activity.getId(), checkin.student.getId()));
            Registration registration = registrationById.get(registrationId);
            if (registration != null
                && java.util.Objects.equals(registration.identityId, checkin.identityId)
                && java.util.Objects.equals(registration.bindingId, checkin.bindingId)) {
                checkinsByRegistration.put(registrationId, checkin);
            } else if (registration != null) {
                mismatchedCheckins.add(registrationId);
            }
        }
        return new ImpactView(id, targetStudentId, accounts, requiresOwnershipReview, candidates.stream().map(row -> {
            Checkin checkin = checkinsByRegistration.get(row.id);
            return new ImpactRow(row.id, row.activity.getId(), row.activity.getTitle(), row.student.getId(),
                checkin == null ? null : checkin.id,
                row.identityId == null ? "UNKNOWN_HISTORICAL" : "CONFIRMED_IDENTITY",
                targetActivities.contains(row.activity.getId()) && !row.student.getId().equals(targetStudentId),
                mismatchedCheckins.contains(row.id));
        }).toList());
    }

    @Transactional(readOnly = true)
    Resource evidence(Long id) {
        requireEnabled();
        BindingAppeal appeal = appeals.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "申诉不存在"));
        if (appeal.evidenceDeletedAt != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "证明材料已清理");
        }
        return evidence.read(appeal.evidenceKey);
    }

    @Transactional
    AppealView reject(Long id, UserAccount reviewer, String note) {
        requireEnabled();
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isBlank() || trimmed.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写拒绝原因（1000字以内）");
        }
        BindingAppeal appeal = appeals.lockById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "申诉不存在"));
        if (!open(appeal)) {
            throw new ApiException(HttpStatus.CONFLICT, "申诉已处理");
        }
        String finalNote = conclusionWithPriorDispute(appeal, trimmed);
        appeal.status = BindingAppeal.Status.REJECTED;
        appeal.reviewer = reviewer;
        appeal.reviewNote = finalNote;
        appeal.reviewedAt = Instant.now();
        notifications.notifyAdmins(null, UserMessage.Kind.REVIEW_REJECTED, "账号申诉已驳回",
            "学号 " + appeal.proposedStudentNumber + " 的申诉已有审核结论。", "appeal:" + appeal.id + ":REJECTED");
        return AppealView.of(appeal);
    }

    @Transactional
    AppealView dispute(Long id, UserAccount reviewer, String reason) {
        requireEnabled();
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isBlank() || trimmed.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写争议原因和待核事项（500字以内）");
        }
        BindingAppeal appeal = appeals.lockById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "申诉不存在"));
        if (appeal.status != BindingAppeal.Status.PENDING || appeal.evidenceDeletedAt != null) {
            throw new ApiException(HttpStatus.CONFLICT, "申诉状态已变化，请刷新后处理");
        }
        appeal.status = BindingAppeal.Status.DISPUTED;
        appeal.reviewer = reviewer;
        appeal.reviewNote = trimmed;
        appeal.reviewedAt = Instant.now();
        return AppealView.of(appeal);
    }

    @Transactional
    AppealView approve(Long id, UserAccount reviewer, AdminBindingAppealController.ApproveRequest request) {
        requireEnabled();
        String name = request.verifiedName().trim();
        String note = request.reason().trim();
        if (name.isBlank() || name.length() > 80 || name.chars().anyMatch(Character::isISOControl)
            || note.isBlank() || request.decisions() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请核对学生姓名、批准依据和每条参与记录");
        }
        BindingAppeal appeal = appeals.lockById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "申诉不存在"));
        if (!open(appeal) || appeal.evidenceDeletedAt != null) {
            throw new ApiException(HttpStatus.CONFLICT, "申诉已处理或证明材料不可用");
        }
        evidence.read(appeal.evidenceKey);
        WechatIdentity identity = identities.lockById(appeal.applicantIdentity.getId())
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "微信身份已变化，请重新核对"));
        if (!appId.equals(identity.getAppId())) {
            throw new ApiException(HttpStatus.CONFLICT, "申诉微信身份与当前小程序不一致");
        }
        WechatStudentBinding current = bindings.findByAppIdAndOpenIdAndUnboundAtIsNull(appId, identity.getOpenId())
            .orElse(null);
        if (appeal.appealType == BindingAppeal.Type.STUDENT_NUMBER_OCCUPIED && current != null
            || appeal.appealType == BindingAppeal.Type.WRONG_STUDENT_NUMBER
                && (appeal.currentBinding == null || current != null
                    && !current.getId().equals(appeal.currentBinding.getId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "微信绑定已变化，请重新核对申诉");
        }
        ImpactView impact = impact(id);
        for (Long activityId : impact.rows().stream().map(ImpactRow::activityId).distinct().sorted().toList()) {
            activities.lockById(activityId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "活动记录已变化，请重新核对"));
        }
        impact = impact(id);
        if (impact.accounts().stream().anyMatch(account -> account.organizer()
            || account.activityCount() > 0)) {
            throw new ApiException(HttpStatus.CONFLICT, "相关账号有组织者权限或负责活动，需先单独裁决管理权");
        }
        UserAccount target = impact.targetStudentId() == null ? null
            : users.lockUserById(impact.targetStudentId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "目标账号已变化，请重新核对"));
        if (target != null && (!target.roles.contains(UserAccount.Role.STUDENT)
            || target.roles.contains(UserAccount.Role.ADMIN))) {
            throw new ApiException(HttpStatus.CONFLICT, "目标账号身份不允许换绑");
        }
        String nameReview = target == null ? "学生证核对姓名：" + name
            : "学生证姓名核对：原登记姓名【" + target.getName() + "】，核对后姓名【" + name + "】";
        String finalNote = conclusionWithPriorDispute(appeal, note + "\n" + nameReview);
        WechatStudentBinding targetBinding = target == null ? null
            : bindings.lockActiveByStudent(appId, target.getId()).orElse(null);
        if (targetBinding != null && (targetBinding.getIdentity().getId().equals(identity.getId())
            || !request.replaceTargetBinding())) {
            throw new ApiException(HttpStatus.CONFLICT, "目标学号已有微信绑定，请明确核准替换原绑定");
        }
        Map<Long, AdminBindingAppealController.AttributionDecisionRequest> decisions;
        try {
            decisions = request.decisions().stream().collect(Collectors.toMap(
                AdminBindingAppealController.AttributionDecisionRequest::registrationId, Function.identity()));
        } catch (IllegalStateException error) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "参与记录决策不可重复");
        }
        if (decisions.size() != impact.rows().size()
            || impact.rows().stream().anyMatch(row -> !decisions.containsKey(row.registrationId()))) {
            throw new ApiException(HttpStatus.CONFLICT, "参与记录已变化，请重新逐条核对");
        }
        Set<Long> transferActivities = new java.util.HashSet<>();
        for (ImpactRow row : impact.rows()) {
            var decision = decisions.get(row.registrationId());
            if (decision.decision() == ParticipationAttributionReview.Decision.DISPUTED
                || decision.reason().trim().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "每条记录须明确转移或保留并填写依据；争议记录暂不能批准");
            }
            if (decision.decision() == ParticipationAttributionReview.Decision.TRANSFER) {
                if (!transferActivities.add(row.activityId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "同一活动有多条历史报名，须先逐条裁决冲突");
                }
                if (row.targetHasRegistration() || row.sourceStudentId().equals(impact.targetStudentId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "目标账号已有同活动记录，不能自动转移");
                }
                Checkin sourceCheckin = checkins.findByActivityIdAndStudentId(
                    row.activityId(), row.sourceStudentId()).orElse(null);
                if (sourceCheckin != null && !sourceCheckin.getId().equals(row.checkinId())
                    || target != null && checkins.existsByActivityIdAndStudentId(row.activityId(), target.getId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "签到来源或目标账号签到冲突，不能转移");
                }
            }
        }
        if (target == null) target = users.saveAndFlush(UserAccount.selfRegisteredStudent(
            name, appeal.proposedStudentNumber));
        else target.name = name;
        Instant now = Instant.now();
        if (current != null) unbindForAppeal(current, reviewer, note, now);
        if (targetBinding != null) unbindForAppeal(targetBinding, reviewer, note, now);
        for (ImpactRow row : impact.rows()) {
            var decision = decisions.get(row.registrationId());
            Registration registration = registrations.findById(row.registrationId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "参与记录已变化，请重新核对"));
            if (!registration.student.getId().equals(row.sourceStudentId())) {
                throw new ApiException(HttpStatus.CONFLICT, "参与记录所属学生已变化，请重新核对");
            }
            if (!registration.activity.getId().equals(row.activityId())) {
                throw new ApiException(HttpStatus.CONFLICT, "参与记录活动已变化，请重新核对");
            }
            if (registration.identityId != null && !registration.identityId.equals(identity.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "参与记录微信来源已变化，请重新核对");
            }
            UserAccount source = registration.student;
            Checkin checkin = row.checkinId() == null ? null : checkins.findById(row.checkinId())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "签到记录已变化，请重新核对"));
            attributionReviews.save(new ParticipationAttributionReview(appeal, registration, checkin,
                source, target, decision.decision(), decision.reason().trim(), reviewer, now));
            if (decision.decision() == ParticipationAttributionReview.Decision.TRANSFER) {
                registration.student = target;
                registration.identityId = identity.getId();
                if (checkin != null) {
                    checkin.student = target;
                    checkin.identityId = identity.getId();
                    checkin.bindingId = registration.bindingId;
                }
            }
        }
        registrations.flush();
        checkins.flush();
        attributionReviews.flush();
        bindings.saveAndFlush(new WechatStudentBinding(identity, target));
        target.revokeWebCredentials();
        appeal.status = BindingAppeal.Status.APPROVED;
        appeal.reviewer = reviewer;
        appeal.reviewNote = finalNote;
        appeal.reviewedAt = now;
        securityAudits.save(new UserSecurityAudit(reviewer, target,
            UserSecurityAudit.Action.BINDING_APPEAL_APPROVED));
        notifications.notifyAdmins(null, UserMessage.Kind.REVIEW_APPROVED, "账号申诉已通过",
            "学号 " + appeal.proposedStudentNumber + " 的申诉已通过。", "appeal:" + appeal.id + ":APPROVED");
        return AppealView.of(appeal);
    }

    @Transactional(readOnly = true)
    List<AttributionReviewView> attributionReviews(Long appealId) {
        requireEnabled();
        if (!appeals.existsById(appealId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "申诉不存在");
        }
        return attributionReviews.findByAppealIdOrderByRegistrationId(appealId)
            .stream().map(AttributionReviewView::of).toList();
    }

    private void unbindForAppeal(WechatStudentBinding binding, UserAccount reviewer, String note, Instant now) {
        binding.unbind(reviewer, note.substring(0, Math.min(note.length(), 500)), now);
        tokens.revokeByBinding(binding.getId(), now);
        UserAccount student = users.lockUserById(binding.getStudent().getId())
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "学生账号已变化，请重新核对"));
        student.revokeWebCredentials();
        securityAudits.save(new UserSecurityAudit(reviewer, student,
            UserSecurityAudit.Action.WECHAT_UNBOUND));
    }

    private AppealChallenge validChallenge(String rawToken) {
        requireEnabled();
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "申诉凭证无效");
        }
        AppealChallenge challenge = challenges.lockByTokenHash(hash(rawToken))
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "申诉凭证无效"));
        if (challenge.consumedAt != null || !Instant.now().isBefore(challenge.expiresAt)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "申诉凭证已失效，请重新开始");
        }
        return challenge;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "账号申诉尚未启用");
        }
    }

    private boolean hasOpenAppeal(Long identityId) {
        return appeals.existsByApplicantIdentityIdAndStatus(identityId, BindingAppeal.Status.PENDING)
            || appeals.existsByApplicantIdentityIdAndStatus(identityId, BindingAppeal.Status.DISPUTED);
    }

    private static boolean open(BindingAppeal appeal) {
        return appeal.status == BindingAppeal.Status.PENDING
            || appeal.status == BindingAppeal.Status.DISPUTED;
    }

    private static String conclusionWithPriorDispute(BindingAppeal appeal, String conclusion) {
        if (appeal.status != BindingAppeal.Status.DISPUTED) return conclusion;
        String combined = conclusion + "\n此前争议待核事项：" + appeal.reviewNote;
        if (combined.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "结论与先前争议原因合计不能超过1000字");
        }
        return combined;
    }

    void ensureEnabled() {
        requireEnabled();
    }

    boolean enabled() {
        return enabled;
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}

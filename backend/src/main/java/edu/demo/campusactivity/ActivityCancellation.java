package edu.demo.campusactivity;

import tools.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.Hibernate;
import org.hibernate.type.SqlTypes;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

@Entity
@Table(name = "activity_change_requests")
class ActivityChangeRequest {
    enum Type { CHANGE, CANCEL }
    enum Status { PENDING, APPROVED, REJECTED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    Activity activity;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false)
    UserAccount requester;
    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 12)
    Type requestType;
    @Column(nullable = false, length = 1000)
    String reason;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "base_snapshot", nullable = false, columnDefinition = "json")
    String baseSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_snapshot", columnDefinition = "json")
    String proposedSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    Status status = Status.PENDING;
    @Column(name = "created_at", nullable = false)
    Instant createdAt = Instant.now();
    @Column(name = "reviewed_at")
    Instant reviewedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    UserAccount reviewer;
    @Column(name = "review_note", length = 1000)
    String reviewNote;

    protected ActivityChangeRequest() {}

    ActivityChangeRequest(Activity activity, UserAccount requester, String reason, String snapshot) {
        this.activity = activity;
        this.requester = requester;
        this.requestType = Type.CANCEL;
        this.reason = reason;
        this.baseSnapshot = snapshot;
    }

    ActivityChangeRequest(Activity activity, UserAccount requester, String reason,
                          String snapshot, String proposal) {
        this(activity, requester, reason, snapshot);
        this.requestType = Type.CHANGE;
        this.proposedSnapshot = proposal;
    }
}

interface ActivityChangeRequestRepository extends JpaRepository<ActivityChangeRequest, Long> {
    Optional<ActivityChangeRequest> findByActivityIdAndStatus(Long activityId, ActivityChangeRequest.Status status);
    List<ActivityChangeRequest> findByStatusOrderByCreatedAtAsc(ActivityChangeRequest.Status status);
    @Query("select request.activity.id from ActivityChangeRequest request where request.id = :id")
    Optional<Long> activityIdById(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from ActivityChangeRequest request where request.id = :id")
    Optional<ActivityChangeRequest> lockById(@Param("id") Long id);
}

@Entity
@Table(name = "activity_mutation_audits")
class ActivityMutationAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    Activity activity;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    UserAccount actor;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "change_request_id")
    ActivityChangeRequest request;
    @Column(nullable = false, length = 32)
    String action;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_snapshot", nullable = false, columnDefinition = "json")
    String beforeSnapshot;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_snapshot", nullable = false, columnDefinition = "json")
    String afterSnapshot;
    @Column(nullable = false, length = 1000)
    String reason;
    @Column(name = "created_at", nullable = false)
    Instant createdAt = Instant.now();

    protected ActivityMutationAudit() {}

    ActivityMutationAudit(Activity activity, UserAccount actor, ActivityChangeRequest request,
                          String action, String before, String after, String reason) {
        this.activity = activity;
        this.actor = actor;
        this.request = request;
        this.action = action;
        this.beforeSnapshot = before;
        this.afterSnapshot = after;
        this.reason = reason;
    }
}

interface ActivityMutationAuditRepository extends JpaRepository<ActivityMutationAudit, Long> {
    List<ActivityMutationAudit> findByActivityIdOrderByIdDesc(Long activityId);
}

@Service
class ActivityCancellationService {
    private record CancellationBaseline(Long id, Long organizerId, String title, String description,
        String articleSummary, String coverImageUrl, String location, Double longitude, Double latitude,
        Instant startsAt, Instant endsAt, Instant registrationOpensAt, Instant registrationClosesAt,
        Instant checkinOpensAt, Instant checkinClosesAt, int capacity) {}
    record RequestView(Long id, Long activityId, String activityTitle, String reason,
                       ActivityChangeRequest.Status status, Instant createdAt,
                       Instant reviewedAt, String reviewerName, String reviewNote) {
        static RequestView of(ActivityChangeRequest request) {
            return new RequestView(request.id, request.activity.getId(), request.activity.getTitle(),
                request.reason, request.status, request.createdAt, request.reviewedAt,
                request.reviewer == null ? null : request.reviewer.getName(), request.reviewNote);
        }
    }
    record MutationView(String action, String reason, String actorName, Instant createdAt,
                        String beforeSnapshot, String afterSnapshot) {
        static MutationView of(ActivityMutationAudit audit) {
            return new MutationView(audit.action, audit.reason, audit.actor.getName(),
                audit.createdAt, audit.beforeSnapshot, audit.afterSnapshot);
        }
    }

    private final ActivityRepository activities;
    private final ActivityChangeRequestRepository requests;
    private final ActivityMutationAuditRepository mutations;
    private final RegistrationRepository registrations;
    private final UserMessageService messages;
    private final CurrentUser current;
    private final ObjectMapper json;

    ActivityCancellationService(ActivityRepository activities, ActivityChangeRequestRepository requests,
                                ActivityMutationAuditRepository mutations, RegistrationRepository registrations,
                                UserMessageService messages, CurrentUser current, ObjectMapper json) {
        this.activities = activities;
        this.requests = requests;
        this.mutations = mutations;
        this.registrations = registrations;
        this.messages = messages;
        this.current = current;
        this.json = json;
    }

    @Transactional
    RequestView request(Long activityId, String reason) {
        UserAccount organizer = requireMobileOrganizer();
        String normalized = reason(reason);
        Activity activity = lockActivity(activityId);
        current.owns(activity);
        requirePublished(activity);
        if (requests.findByActivityIdAndStatus(activityId, ActivityChangeRequest.Status.PENDING).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "该活动已有待审核变更");
        }
        ActivityChangeRequest request = requests.saveAndFlush(
            new ActivityChangeRequest(activity, organizer, normalized, baseline(activity)));
        return RequestView.of(request);
    }

    @Transactional(readOnly = true)
    RequestView pendingForOrganizer(Long activityId) {
        requireMobileOrganizer();
        Activity activity = activities.findById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        current.owns(activity);
        return requests.findByActivityIdAndStatus(activityId, ActivityChangeRequest.Status.PENDING)
            .filter(request -> request.requestType == ActivityChangeRequest.Type.CANCEL)
            .map(RequestView::of).orElse(null);
    }

    @Transactional(readOnly = true)
    List<RequestView> pendingForAdmin() {
        current.require(UserAccount.Role.ADMIN);
        return requests.findByStatusOrderByCreatedAtAsc(ActivityChangeRequest.Status.PENDING)
            .stream().filter(request -> request.requestType == ActivityChangeRequest.Type.CANCEL)
            .map(RequestView::of).toList();
    }

    @Transactional(readOnly = true)
    RequestView forAdmin(Long requestId) {
        current.require(UserAccount.Role.ADMIN);
        return requests.findById(requestId)
            .filter(request -> request.requestType == ActivityChangeRequest.Type.CANCEL)
            .map(RequestView::of)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "取消申请不存在"));
    }

    @Transactional
    RequestView review(Long requestId, boolean approved, String note) {
        UserAccount admin = current.require(UserAccount.Role.ADMIN);
        Long activityId = requests.activityIdById(requestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "取消申请不存在"));
        Activity activity = lockActivity(activityId);
        ActivityChangeRequest request = requests.lockById(requestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "取消申请不存在"));
        if (request.requestType != ActivityChangeRequest.Type.CANCEL
            || request.status != ActivityChangeRequest.Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "取消申请已处理或类型不符");
        }
        String normalizedNote = approved ? optionalNote(note) : reason(note);
        if (approved) requirePublished(activity);
        if (approved && !request.baseSnapshot.equals(baseline(activity))) {
            throw new ApiException(HttpStatus.CONFLICT, "活动已变更，请先重新核对取消申请");
        }
        request.status = approved ? ActivityChangeRequest.Status.APPROVED : ActivityChangeRequest.Status.REJECTED;
        request.reviewer = admin;
        request.reviewedAt = Instant.now();
        request.reviewNote = normalizedNote;
        if (approved) cancel(activity, admin, request, "CANCEL_APPROVED", request.reason);
        messages.send(request.requester, activity,
            approved ? UserMessage.Kind.REVIEW_APPROVED : UserMessage.Kind.REVIEW_REJECTED,
            approved ? "活动取消申请已通过" : "活动取消申请未通过",
            approved ? "“" + activity.title + "”已取消。" : "“" + activity.title + "”的取消申请未通过，请查看审核结果。",
            "activity-cancel-review:" + request.id);
        return RequestView.of(request);
    }

    @Transactional
    ActivityView emergency(Long activityId, String reason) {
        UserAccount admin = current.require(UserAccount.Role.ADMIN);
        String normalized = reason(reason);
        Activity activity = lockActivity(activityId);
        requirePublished(activity);
        requests.findByActivityIdAndStatus(activityId, ActivityChangeRequest.Status.PENDING)
            .ifPresent(pending -> {
                pending.status = ActivityChangeRequest.Status.REJECTED;
                pending.reviewer = admin;
                pending.reviewedAt = Instant.now();
                pending.reviewNote = "管理员紧急取消，原申请终止";
            });
        cancel(activity, admin, null, "CANCEL_EMERGENCY", normalized);
        return ActivityView.of(activity);
    }

    @Transactional(readOnly = true)
    List<MutationView> mutations(Long activityId) {
        current.require(UserAccount.Role.ADMIN);
        if (!activities.existsById(activityId)) throw new ApiException(HttpStatus.NOT_FOUND, "活动不存在");
        return mutations.findByActivityIdOrderByIdDesc(activityId).stream().map(MutationView::of).toList();
    }

    private void cancel(Activity activity, UserAccount admin, ActivityChangeRequest request,
                        String action, String reason) {
        String before = snapshot(activity);
        activity.status = Activity.Status.CANCELLED;
        activity.cancelReason = reason;
        activity.checkinTokenHash = null;
        activity.checkinTokenExpiresAt = null;
        mutations.save(new ActivityMutationAudit(activity, admin, request, action,
            before, snapshot(activity), reason));
        String notification = "“" + activity.title + "”已取消。原因：" + reason;
        if (notification.length() > 500) notification = notification.substring(0, 499) + "…";
        if (request == null) {
            messages.notifyAdmins(activity, UserMessage.Kind.ACTIVITY_CANCELLED, "活动已取消", notification,
                "activity-cancel:" + activity.id);
            messages.send(activity.organizer, activity, UserMessage.Kind.ACTIVITY_CANCELLED, "活动已取消", notification,
                "activity-cancel-owner:" + activity.id);
        }
        for (Registration registration : registrations.findByActivityIdOrderByRegisteredAtAsc(activity.id)) {
            if (registration.status == Registration.Status.ACTIVE && registration.identityId != null) {
                messages.sendToIdentity(registration.student, registration.identityId, activity,
                    UserMessage.Kind.ACTIVITY_CANCELLED, "活动已取消", notification,
                    "activity-cancel:" + activity.id + ":" + registration.id);
            }
        }
    }

    private Activity lockActivity(Long id) {
        return Hibernate.unproxy(activities.lockById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在")), Activity.class);
    }

    private static void requirePublished(Activity activity) {
        if (activity.getStatus() != Activity.Status.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "只有已发布活动可以申请或执行取消");
        }
    }

    private UserAccount requireMobileOrganizer() {
        UserAccount user = current.require(UserAccount.Role.STUDENT, UserAccount.Role.ORGANIZER);
        if (!user.roles.contains(UserAccount.Role.STUDENT)
            || !user.roles.contains(UserAccount.Role.ORGANIZER)
            || user.roles.contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅学生组织者可申请取消");
        }
        return user;
    }

    private static String reason(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写500字以内的取消原因");
        }
        return normalized;
    }

    private static String optionalNote(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "审核意见不能超过1000字");
        }
        return normalized;
    }

    private String snapshot(Activity activity) {
        return json.writeValueAsString(ActivityView.of(activity));
    }

    private String baseline(Activity activity) {
        return json.writeValueAsString(new CancellationBaseline(activity.id, activity.organizer.getId(),
            activity.title, activity.description, activity.articleSummary, activity.coverImageUrl,
            activity.location, activity.longitude, activity.latitude, activity.startsAt, activity.endsAt,
            activity.registrationOpensAt, activity.registrationClosesAt, activity.checkinOpensAt,
            activity.checkinClosesAt, activity.capacity));
    }
}

package edu.demo.campusactivity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CheckinService {
    private final ActivityRepository activities;
    private final ActivityService activityService;
    private final RegistrationRepository registrations;
    private final CheckinRepository checkins;
    private final ActivityAuditRepository audits;
    private final UserRepository users;
    private final CurrentUser current;
    private final CheckinTokenSigner tokenSigner;

    CheckinService(ActivityRepository activities, ActivityService activityService,
                   RegistrationRepository registrations, CheckinRepository checkins,
                   ActivityAuditRepository audits,
                   UserRepository users, CurrentUser current, CheckinTokenSigner tokenSigner) {
        this.activities = activities;
        this.activityService = activityService;
        this.registrations = registrations;
        this.checkins = checkins;
        this.audits = audits;
        this.users = users;
        this.current = current;
        this.tokenSigner = tokenSigner;
    }

    @Transactional
    Map<String, Object> issueToken(Long activityId) {
        UserAccount actor = current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        Activity activity = activities.lockById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        current.owns(activity);
        return issueAndAudit(activity, actor);
    }

    @Transactional
    Map<String, Object> issueAdminToken(Long activityId) {
        UserAccount admin = current.require(UserAccount.Role.ADMIN);
        Activity activity = activities.lockById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        return issueAndAudit(activity, admin);
    }

    private Map<String, Object> issueAndAudit(Activity activity, UserAccount actor) {
        Map<String, Object> token = createToken(activity);
        if (actor.roles.contains(UserAccount.Role.ADMIN)) {
            audits.save(new ActivityAudit(activity, actor, ActivityAudit.Action.QR_ISSUED));
        }
        return token;
    }

    private Map<String, Object> createToken(Activity activity) {
        Instant now = Instant.now();
        if (activity.status != Activity.Status.PUBLISHED || now.isBefore(activity.checkinOpensAt)
            || now.isAfter(activity.checkinClosesAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "当前不在签到时间内");
        }
        CheckinTokenSigner.Issued issued = tokenSigner.issue(activity.getId(), now);
        return Map.of("token", issued.token(), "expiresAt", issued.expiresAt());
    }

    @Transactional
    RegistrationView scan(Long activityId, String token) {
        UserAccount student = current.require(UserAccount.Role.STUDENT);
        Activity activity = activities.lockById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        Instant now = Instant.now();
        if (activity.status != Activity.Status.PUBLISHED || now.isBefore(activity.checkinOpensAt)
            || now.isAfter(activity.checkinClosesAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "当前不在签到时间内");
        }
        if (token == null || token.length() > 256 || !(
            token.startsWith("v2.") ? tokenSigner.valid(activityId, token, now) : validLegacy(activity, token, now))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "签到二维码已失效，请重新扫码");
        }
        return save(activity, student, null, Checkin.Method.QR, null);
    }

    @Transactional
    RegistrationView manual(Long activityId, Long studentId, String reason) {
        UserAccount operator = current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        Activity activity = activities.lockById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        current.owns(activity);
        if (activity.status != Activity.Status.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "活动尚未发布");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "补签原因不能为空且不能超过 500 字");
        }
        UserAccount student = users.findById(studentId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "学生不存在"));
        if (!student.roles.contains(UserAccount.Role.STUDENT)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "只能为学生补签");
        }
        return save(activity, student, operator, Checkin.Method.MANUAL, reason.trim());
    }

    @Transactional(readOnly = true)
    StatsView stats(Long activityId) {
        Activity activity = activityService.owned(activityId);
        return StatsView.of(activity, checkins.countByActivityId(activityId));
    }

    @Transactional(readOnly = true)
    StatsView adminStats(Long activityId) {
        current.require(UserAccount.Role.ADMIN);
        Activity activity = activityService.find(activityId);
        return StatsView.of(activity, checkins.countByActivityId(activityId));
    }

    @Transactional(readOnly = true)
    List<AdminActivityOverview> adminOverview() {
        current.require(UserAccount.Role.ADMIN);
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : checkins.countByActivityGrouped()) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return activities.findAllByOrderByCreatedAtDesc().stream()
            .map(a -> new AdminActivityOverview(ActivityView.of(a),
                StatsView.of(a, counts.getOrDefault(a.id, 0L))))
            .toList();
    }

    private RegistrationView save(Activity activity, UserAccount student, UserAccount operator,
                                  Checkin.Method method, String reason) {
        MiniappAttribution attribution = current.miniappAttribution();
        Registration registration = registrations.findByActivityIdAndStudentId(activity.getId(), student.getId())
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "该学生未报名"));
        if (method == Checkin.Method.QR && attribution != null
            && !attribution.identityId().equals(registration.identityId)) {
            throw new ApiException(HttpStatus.CONFLICT, "该活动的历史报名归属待管理员核实");
        }
        if (registration.status != Registration.Status.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "该学生的报名已取消");
        }
        if (checkins.existsByActivityIdAndStudentId(activity.getId(), student.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "该学生已签到");
        }
        Checkin checkin = new Checkin(activity, student, operator, method, reason);
        checkin.identityId = registration.identityId;
        checkin.bindingId = registration.bindingId;
        checkins.save(checkin);
        return RegistrationView.of(registration, checkin);
    }

    private String hash(String token) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean validLegacy(Activity activity, String token, Instant now) {
        return token.length() <= 100 && activity.checkinTokenHash != null
            && activity.checkinTokenExpiresAt != null && now.isBefore(activity.checkinTokenExpiresAt)
            && MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                activity.checkinTokenHash.getBytes(StandardCharsets.US_ASCII));
    }
}

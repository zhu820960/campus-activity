package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hibernate.Hibernate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class ActivityPublishedChangeService {
    record ChangeView(Long id, Long activityId, String activityTitle, String reason,
                      ActivityService.ActivityInput proposed, ActivityChangeRequest.Status status,
                      Instant createdAt, Instant reviewedAt, String reviewerName, String reviewNote) {}

    private final ActivityRepository activities;
    private final ActivityChangeRequestRepository requests;
    private final ActivityMutationAuditRepository audits;
    private final RegistrationRepository registrations;
    private final CheckinRepository checkins;
    private final ActivityService activityService;
    private final UserMessageService messages;
    private final CurrentUser current;
    private final ObjectMapper json;

    ActivityPublishedChangeService(ActivityRepository activities, ActivityChangeRequestRepository requests,
                                   ActivityMutationAuditRepository audits, RegistrationRepository registrations,
                                   CheckinRepository checkins, ActivityService activityService,
                                   UserMessageService messages, CurrentUser current, ObjectMapper json) {
        this.activities = activities;
        this.requests = requests;
        this.audits = audits;
        this.registrations = registrations;
        this.checkins = checkins;
        this.activityService = activityService;
        this.messages = messages;
        this.current = current;
        this.json = json;
    }

    @Transactional
    ChangeView request(Long activityId, ActivityService.ActivityInput proposed, String reason) {
        UserAccount organizer = requireMobileOrganizer();
        String normalized = reason(reason);
        Activity activity = lockActivity(activityId);
        current.owns(activity);
        requirePublished(activity);
        validatePublishedUpdate(activity, proposed);
        String baseline = content(activity);
        String proposal = json.writeValueAsString(proposed);
        if (baseline.equals(proposal)) throw new ApiException(HttpStatus.BAD_REQUEST, "活动内容没有变化");
        requireNoPending(activityId);
        return view(requests.saveAndFlush(new ActivityChangeRequest(
            activity, organizer, normalized, baseline, proposal)));
    }

    @Transactional(readOnly = true)
    ChangeView pendingForOrganizer(Long activityId) {
        requireMobileOrganizer();
        Activity activity = activities.findById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        current.owns(activity);
        return requests.findByActivityIdAndStatus(activityId, ActivityChangeRequest.Status.PENDING)
            .filter(request -> request.requestType == ActivityChangeRequest.Type.CHANGE)
            .map(this::view).orElse(null);
    }

    @Transactional(readOnly = true)
    List<ChangeView> pendingForAdmin() {
        current.require(UserAccount.Role.ADMIN);
        return requests.findByStatusOrderByCreatedAtAsc(ActivityChangeRequest.Status.PENDING).stream()
            .filter(request -> request.requestType == ActivityChangeRequest.Type.CHANGE)
            .map(this::view).toList();
    }

    @Transactional
    ChangeView review(Long requestId, boolean approved, String note) {
        UserAccount admin = current.require(UserAccount.Role.ADMIN);
        Long activityId = requests.activityIdById(requestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动变更申请不存在"));
        Activity activity = lockActivity(activityId);
        ActivityChangeRequest request = requests.lockById(requestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动变更申请不存在"));
        if (request.requestType != ActivityChangeRequest.Type.CHANGE
            || request.status != ActivityChangeRequest.Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "变更申请已处理或类型不符");
        }
        String reviewNote = approved ? optionalNote(note) : reason(note);
        if (approved) {
            requirePublished(activity);
            if (!request.baseSnapshot.equals(content(activity))) {
                throw new ApiException(HttpStatus.CONFLICT, "活动已变更，请先重新核对变更申请");
            }
            ActivityService.ActivityInput proposed = proposal(request);
            validatePublishedUpdate(activity, proposed);
            apply(activity, proposed, admin, request, "CHANGE_APPROVED", request.reason);
        }
        request.status = approved ? ActivityChangeRequest.Status.APPROVED : ActivityChangeRequest.Status.REJECTED;
        request.reviewer = admin;
        request.reviewedAt = Instant.now();
        request.reviewNote = reviewNote;
        messages.send(request.requester, activity,
            approved ? UserMessage.Kind.REVIEW_APPROVED : UserMessage.Kind.REVIEW_REJECTED,
            approved ? "活动变更已通过" : "活动变更未通过",
            approved ? "“" + activity.title + "”的变更已生效。" : "“" + activity.title + "”的变更未通过，请查看审核意见。",
            "activity-change-review:" + request.id);
        return view(request);
    }

    @Transactional
    ActivityView direct(Long activityId, ActivityService.ActivityInput proposed, String reason) {
        UserAccount admin = current.require(UserAccount.Role.ADMIN);
        String normalized = reason(reason);
        Activity activity = lockActivity(activityId);
        requirePublished(activity);
        requireNoPending(activityId);
        validatePublishedUpdate(activity, proposed);
        if (content(activity).equals(json.writeValueAsString(proposed))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "活动内容没有变化");
        }
        apply(activity, proposed, admin, null, "CHANGE_DIRECT", normalized);
        return ActivityView.of(activity);
    }

    private void apply(Activity activity, ActivityService.ActivityInput input, UserAccount actor,
                       ActivityChangeRequest request, String action, String reason) {
        String before = json.writeValueAsString(ActivityView.of(activity));
        activityService.fill(activity, input);
        if (request == null) {
            String event = "activity-update:" + activity.id + ":" + java.util.UUID.randomUUID();
            messages.notifyAdmins(activity, UserMessage.Kind.ACTIVITY_UPDATED, "活动已更新", "“" + activity.title + "”的修改已生效。", event);
            messages.send(activity.organizer, activity, UserMessage.Kind.ACTIVITY_UPDATED, "活动已更新", "“" + activity.title + "”的修改已生效。", event + ":owner");
        }
        String after = json.writeValueAsString(ActivityView.of(activity));
        ActivityMutationAudit audit = audits.saveAndFlush(new ActivityMutationAudit(
            activity, actor, request, action, before, after, reason));
        String body = "“" + activity.title + "”的信息已更新，请重新查看活动时间、地点及报名安排。";
        for (Registration registration : registrations.findByActivityIdOrderByRegisteredAtAsc(activity.id)) {
            if (registration.status == Registration.Status.ACTIVE && registration.identityId != null) {
                messages.sendToIdentity(registration.student, registration.identityId, activity,
                    UserMessage.Kind.ACTIVITY_UPDATED, "活动信息已更新", body,
                    "activity-change:" + audit.id + ":" + registration.id);
            }
        }
    }

    private void validatePublishedUpdate(Activity activity, ActivityService.ActivityInput input) {
        activityService.validateContent(input);
        if (input.longitude() == null || input.latitude() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "已发布活动必须保留地图坐标");
        }
        if (input.capacity() < activity.registeredCount) {
            throw new ApiException(HttpStatus.CONFLICT, "人数上限不能低于现有有效报名人数");
        }
        boolean logisticsChanged = !Objects.equals(activity.location, input.location())
            || !Objects.equals(activity.longitude, input.longitude())
            || !Objects.equals(activity.latitude, input.latitude())
            || !Objects.equals(activity.startsAt, input.startsAt())
            || !Objects.equals(activity.endsAt, input.endsAt())
            || !Objects.equals(activity.registrationOpensAt, input.registrationOpensAt())
            || !Objects.equals(activity.registrationClosesAt, input.registrationClosesAt())
            || !Objects.equals(activity.checkinOpensAt, input.checkinOpensAt())
            || !Objects.equals(activity.checkinClosesAt, input.checkinClosesAt())
            || activity.capacity != input.capacity();
        if (logisticsChanged && (!activity.startsAt.isAfter(Instant.now())
            || checkins.countByActivityId(activity.id) > 0)) {
            throw new ApiException(HttpStatus.CONFLICT, "活动开始或已有签到后，只能修改文案与封面");
        }
        if (activity.startsAt.isAfter(Instant.now())) activityService.validate(input);
    }

    private void requireNoPending(Long activityId) {
        if (requests.findByActivityIdAndStatus(activityId, ActivityChangeRequest.Status.PENDING).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "该活动已有待审核变更");
        }
    }

    private ChangeView view(ActivityChangeRequest request) {
        return new ChangeView(request.id, request.activity.getId(), request.activity.getTitle(),
            request.reason, proposal(request), request.status, request.createdAt, request.reviewedAt,
            request.reviewer == null ? null : request.reviewer.getName(), request.reviewNote);
    }

    private ActivityService.ActivityInput proposal(ActivityChangeRequest request) {
        return json.readValue(request.proposedSnapshot, ActivityService.ActivityInput.class);
    }

    private String content(Activity activity) {
        return json.writeValueAsString(new ActivityService.ActivityInput(activity.title, activity.description,
            activity.articleSummary, activity.coverImageUrl, activity.location, activity.longitude,
            activity.latitude, activity.startsAt, activity.endsAt, activity.registrationOpensAt,
            activity.registrationClosesAt, activity.checkinOpensAt, activity.checkinClosesAt, activity.capacity));
    }

    private Activity lockActivity(Long id) {
        return Hibernate.unproxy(activities.lockById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在")), Activity.class);
    }

    private static void requirePublished(Activity activity) {
        if (activity.getStatus() != Activity.Status.PUBLISHED) {
            throw new ApiException(HttpStatus.CONFLICT, "只有已发布活动可以修改");
        }
    }

    private UserAccount requireMobileOrganizer() {
        UserAccount user = current.require(UserAccount.Role.STUDENT, UserAccount.Role.ORGANIZER);
        if (!user.roles.contains(UserAccount.Role.STUDENT)
            || !user.roles.contains(UserAccount.Role.ORGANIZER)
            || user.roles.contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅学生组织者可提交活动变更");
        }
        return user;
    }

    private static String reason(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写500字以内的修改原因");
        }
        return normalized;
    }

    private static String optionalNote(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 1000) throw new ApiException(HttpStatus.BAD_REQUEST, "审核意见不能超过1000字");
        return normalized;
    }
}

package edu.demo.campusactivity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ActivityService {
    record ActivityInput(@NotBlank @Size(max = 160) String title,
                         @NotBlank @Size(max = 5000) String description,
                         @Size(max = 300) String articleSummary,
                         @Size(max = 1000) String coverImageUrl,
                         @NotBlank @Size(max = 200) String location,
                         Double longitude, Double latitude,
                         @NotNull Instant startsAt, @NotNull Instant endsAt,
                         @NotNull Instant registrationOpensAt, @NotNull Instant registrationClosesAt,
                         @NotNull Instant checkinOpensAt, @NotNull Instant checkinClosesAt,
                         @Min(1) int capacity) {}

    private final ActivityRepository activities;
    private final ActivityAuditRepository audits;
    private final CurrentUser current;
    private final UserMessageService messages;

    ActivityService(ActivityRepository activities, ActivityAuditRepository audits, CurrentUser current,
                    UserMessageService messages) {
        this.activities = activities;
        this.audits = audits;
        this.current = current;
        this.messages = messages;
    }

    Activity find(Long id) {
        return activities.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
    }

    Activity owned(Long id) {
        Activity activity = find(id);
        current.owns(activity);
        return activity;
    }

    @Transactional(readOnly = true)
    ActivityView ownedView(Long id) {
        return ActivityView.of(owned(id));
    }

    @Transactional(readOnly = true)
    List<ActivityView> published() {
        return activities.findByStatusOrderByStartsAtAsc(Activity.Status.PUBLISHED)
            .stream().map(ActivityView::of).toList();
    }

    @Transactional(readOnly = true)
    ActivityView visible(Long id) {
        Activity a = find(id);
        if (a.status != Activity.Status.PUBLISHED && a.status != Activity.Status.CANCELLED) current.owns(a);
        return ActivityView.of(a);
    }

    @Transactional(readOnly = true)
    List<ActivityView> mine() {
        UserAccount user = current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        return activities.findByOrganizerIdOrderByCreatedAtDesc(user.getId()).stream()
            .map(ActivityView::of).toList();
    }

    @Transactional
    ActivityView create(ActivityInput input) {
        UserAccount user = current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        validate(input);
        Activity a = new Activity();
        a.organizer = user;
        fill(a, input);
        activities.save(a);
        if (user.roles.contains(UserAccount.Role.ADMIN)) {
            audits.save(new ActivityAudit(a, user, ActivityAudit.Action.CREATED));
        }
        return ActivityView.of(a);
    }

    @Transactional
    ActivityView createForAdmin(ActivityInput input) {
        current.require(UserAccount.Role.ADMIN);
        return create(input);
    }

    @Transactional
    ActivityView edit(Long id, ActivityInput input) {
        UserAccount editor = current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        boolean auditAdmin = editor.roles.contains(UserAccount.Role.ADMIN);
        Activity a = owned(id);
        if (a.status != Activity.Status.DRAFT && a.status != Activity.Status.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "只有草稿或被驳回的活动可以编辑");
        }
        validate(input);
        fill(a, input);
        a.reviewNote = null;
        if (auditAdmin) {
            audits.save(new ActivityAudit(a, editor, ActivityAudit.Action.EDITED));
        }
        return ActivityView.of(a);
    }

    @Transactional
    ActivityView editOwnForAdmin(Long id, ActivityInput input) {
        current.require(UserAccount.Role.ADMIN);
        return edit(id, input);
    }

    @Transactional
    ActivityView submit(Long id) {
        UserAccount user = current.require(UserAccount.Role.ORGANIZER);
        if (user.roles.contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "管理员创建的活动请直接发布");
        }
        Activity a = owned(id);
        if (a.status != Activity.Status.DRAFT && a.status != Activity.Status.REJECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "活动当前不能提交审核");
        }
        if (!a.startsAt.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "活动已开始，无法提交审核，请先修改活动时间");
        }
        requireRegistrationStillOpen(a, Instant.now());
        requireCoordinates(a);
        a.status = Activity.Status.PENDING;
        a.reviewNote = null;
        return ActivityView.of(a);
    }

    @Transactional
    ActivityView publishOwnForAdmin(Long id) {
        UserAccount admin = current.require(UserAccount.Role.ADMIN);
        Activity a = owned(id);
        if (a.status != Activity.Status.DRAFT && a.status != Activity.Status.REJECTED
            && a.status != Activity.Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "活动当前不能发布");
        }
        if (!a.startsAt.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "活动已开始，不能发布");
        }
        requireRegistrationStillOpen(a, Instant.now());
        requireCoordinates(a);
        a.status = Activity.Status.PUBLISHED;
        a.publishedAt = Instant.now();
        a.registrationOpensAt = a.publishedAt;
        a.reviewNote = null;
        audits.save(new ActivityAudit(a, admin, ActivityAudit.Action.DIRECT_PUBLISHED));
        messages.notifyAdmins(a, UserMessage.Kind.REVIEW_APPROVED, "活动已发布", "“" + a.title + "”已直接发布。",
            "direct-publish:" + a.id);
        return ActivityView.of(a);
    }

    @Transactional(readOnly = true)
    List<ActivityView> pending() {
        current.require(UserAccount.Role.ADMIN);
        return activities.findByStatusOrderByCreatedAtAsc(Activity.Status.PENDING)
            .stream().map(ActivityView::of).toList();
    }

    @Transactional(readOnly = true)
    ActivityView visibleToAdmin(Long id) {
        current.require(UserAccount.Role.ADMIN);
        return ActivityView.of(find(id));
    }

    @Transactional
    ActivityView review(Long id, boolean approved, String note) {
        UserAccount admin = current.require(UserAccount.Role.ADMIN);
        Activity a = find(id);
        if (a.status != Activity.Status.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "活动不是待审核状态");
        }
        if (a.organizer.getId().equals(admin.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "管理员创建的活动请直接发布");
        }
        if (!approved && (note == null || note.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "驳回时请填写原因");
        }
        if (note != null && note.trim().length() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "审核意见不能超过500字");
        }
        if (approved && !a.startsAt.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "活动已开始，无法通过审核，请驳回后修改活动时间");
        }
        if (approved) {
            requireRegistrationStillOpen(a, Instant.now());
            requireCoordinates(a);
        }
        a.status = approved ? Activity.Status.PUBLISHED : Activity.Status.REJECTED;
        if (approved) {
            a.publishedAt = Instant.now();
            a.registrationOpensAt = a.publishedAt;
        }
        a.reviewNote = approved ? null : note.trim();
        ActivityAudit event = audits.save(new ActivityAudit(a, admin, approved
            ? ActivityAudit.Action.REVIEW_APPROVED : ActivityAudit.Action.REVIEW_REJECTED,
            note == null || note.isBlank() ? null : note.trim()));
        messages.send(a.organizer, a,
            approved ? UserMessage.Kind.REVIEW_APPROVED : UserMessage.Kind.REVIEW_REJECTED,
            approved ? "活动审核通过" : "活动审核未通过",
            approved ? "“" + a.title + "”已发布。" : "“" + a.title + "”未通过审核，请查看审核意见。",
            "review:" + event.id);
        return ActivityView.of(a);
    }

    void validate(ActivityInput input) {
        validateContent(input);
        if (input.longitude() != null || input.latitude() != null) {
            if (input.longitude() == null || input.latitude() == null
                || !Double.isFinite(input.longitude()) || !Double.isFinite(input.latitude())
                || input.longitude() < -180 || input.longitude() > 180
                || input.latitude() < -90 || input.latitude() > 90) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "请在地图上选择有效的活动地点");
            }
        }
        if (!input.registrationOpensAt().isBefore(input.registrationClosesAt())
            || input.registrationClosesAt().isAfter(input.startsAt())
            || !input.startsAt().isBefore(input.endsAt())
            || !input.checkinOpensAt().isBefore(input.checkinClosesAt())
            || input.checkinClosesAt().isAfter(input.endsAt())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请检查报名、活动和签到时间的先后顺序");
        }
        if (input.startsAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "活动开始时间不能早于当前时间");
        }
    }

    void validateContent(ActivityInput input) {
        if (input.title() == null || input.title().isBlank() || input.title().length() > 160
            || input.description() == null || input.description().isBlank() || input.description().length() > 5000
            || input.location() == null || input.location().isBlank() || input.location().length() > 200
            || input.capacity() < 1
            || (input.articleSummary() != null && input.articleSummary().length() > 300)
            || (input.coverImageUrl() != null && input.coverImageUrl().length() > 1000)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请检查活动内容和人数上限");
        }
        if (input.coverImageUrl() != null && !input.coverImageUrl().isBlank()
            && !input.coverImageUrl().startsWith("https://")
            && !input.coverImageUrl().startsWith("/images/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "封面图片需使用 HTTPS 地址");
        }
    }

    private void requireCoordinates(Activity activity) {
        if (activity.longitude == null || activity.latitude == null) {
            throw new ApiException(HttpStatus.CONFLICT, "发布前请在地图上选择活动地点");
        }
    }

    static void requireRegistrationStillOpen(Activity activity, Instant now) {
        if (!activity.registrationClosesAt.isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "报名截止时间已过，请先调整活动时间");
        }
    }

    void fill(Activity a, ActivityInput input) {
        a.title = input.title().trim();
        a.description = input.description().trim();
        a.articleSummary = input.articleSummary() == null ? null : input.articleSummary().trim();
        a.coverImageUrl = input.coverImageUrl() == null ? null : input.coverImageUrl().trim();
        a.location = input.location().trim();
        a.longitude = input.longitude();
        a.latitude = input.latitude();
        a.startsAt = input.startsAt();
        a.endsAt = input.endsAt();
        a.registrationOpensAt = input.registrationOpensAt();
        a.registrationClosesAt = input.registrationClosesAt();
        a.checkinOpensAt = input.checkinOpensAt();
        a.checkinClosesAt = input.checkinClosesAt();
        a.capacity = input.capacity();
    }
}

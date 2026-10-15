package edu.demo.campusactivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

record ActivityFeedbackView(Long id, Long activityId, String studentName, int rating, String comment,
                            Instant createdAt, Instant updatedAt, Instant hiddenAt, String hiddenReason) {
    static ActivityFeedbackView of(ActivityFeedback feedback) {
        return new ActivityFeedbackView(feedback.id, feedback.activity.getId(), feedback.student.getName(),
            feedback.rating, feedback.comment, feedback.createdAt, feedback.updatedAt,
            feedback.hiddenAt, feedback.hiddenReason);
    }
}

record FeedbackSummary(long count, double averageRating) {}

@Service
class ActivityFeedbackService {
    record Input(@Min(1) @Max(5) int rating, @NotBlank @Size(max = 1000) String comment) {}

    private final ActivityRepository activities;
    private final ActivityFeedbackRepository feedback;
    private final CheckinRepository checkins;
    private final ActivityAuditRepository audits;
    private final CurrentUser current;

    ActivityFeedbackService(ActivityRepository activities, ActivityFeedbackRepository feedback,
                            CheckinRepository checkins, ActivityAuditRepository audits, CurrentUser current) {
        this.activities = activities;
        this.feedback = feedback;
        this.checkins = checkins;
        this.audits = audits;
        this.current = current;
    }

    @Transactional
    ActivityFeedbackView submit(Long activityId, Input input) {
        UserAccount student = current.require(UserAccount.Role.STUDENT);
        Activity activity = activities.lockById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        if (activity.status != Activity.Status.PUBLISHED || Instant.now().isBefore(activity.endsAt)
            || !checkins.existsByActivityIdAndStudentId(activityId, student.id)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "活动结束并完成签到后才能评价");
        }
        String comment = input.comment().trim();
        if (comment.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "请填写评价内容");
        ActivityFeedback item = feedback.findByActivityIdAndStudentId(activityId, student.id)
            .orElseGet(() -> new ActivityFeedback(activity, student, input.rating(), comment));
        if (item.hiddenAt != null) {
            throw new ApiException(HttpStatus.CONFLICT, "该评价已被管理员隐藏，不能修改");
        }
        item.rating = input.rating();
        item.comment = comment;
        item.updatedAt = Instant.now();
        return ActivityFeedbackView.of(feedback.save(item));
    }

    @Transactional(readOnly = true)
    ActivityFeedbackView mine(Long activityId) {
        UserAccount student = current.require(UserAccount.Role.STUDENT);
        return feedback.findByActivityIdAndStudentId(activityId, student.id)
            .map(ActivityFeedbackView::of).orElse(null);
    }

    @Transactional(readOnly = true)
    List<ActivityFeedbackView> forOrganizer(Long activityId) {
        Activity activity = activities.findById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        current.require(UserAccount.Role.ORGANIZER);
        current.owns(activity);
        return feedback.findByActivityIdOrderByCreatedAtDesc(activityId).stream()
            .filter(item -> item.hiddenAt == null).map(ActivityFeedbackView::of).toList();
    }

    @Transactional(readOnly = true)
    List<ActivityFeedbackView> forAdmin(Long activityId) {
        current.require(UserAccount.Role.ADMIN);
        if (!activities.existsById(activityId)) throw new ApiException(HttpStatus.NOT_FOUND, "活动不存在");
        return feedback.findByActivityIdOrderByCreatedAtDesc(activityId).stream()
            .map(ActivityFeedbackView::of).toList();
    }

    @Transactional(readOnly = true)
    FeedbackSummary summary(Long activityId) {
        UserAccount user = current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER);
        Activity activity = activities.findById(activityId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "活动不存在"));
        if (!user.getRoles().contains(UserAccount.Role.ADMIN)) current.owns(activity);
        List<ActivityFeedback> visible = feedback.findByActivityIdOrderByCreatedAtDesc(activityId).stream()
            .filter(item -> item.hiddenAt == null).toList();
        double average = visible.stream().mapToInt(item -> item.rating).average().orElse(0);
        return new FeedbackSummary(visible.size(), average);
    }

    @Transactional
    ActivityFeedbackView hide(Long id, String reason) {
        UserAccount admin = current.require(UserAccount.Role.ADMIN);
        String note = reason == null ? "" : reason.trim();
        if (note.isEmpty() || note.length() > 300) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写300字以内的处理原因");
        }
        ActivityFeedback item = feedback.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "评价不存在"));
        if (item.hiddenAt != null) throw new ApiException(HttpStatus.CONFLICT, "评价已隐藏");
        item.hiddenAt = Instant.now();
        item.hiddenReason = note;
        item.moderator = admin;
        audits.save(new ActivityAudit(item.activity, admin, ActivityAudit.Action.FEEDBACK_HIDDEN));
        return ActivityFeedbackView.of(item);
    }
}

@RestController
@RequestMapping("/api/miniapp/activities/{activityId}/feedback")
class MiniappActivityFeedbackController {
    private final ActivityFeedbackService feedback;
    MiniappActivityFeedbackController(ActivityFeedbackService feedback) { this.feedback = feedback; }
    @GetMapping ActivityFeedbackView mine(@PathVariable Long activityId) { return feedback.mine(activityId); }
    @PutMapping ActivityFeedbackView submit(@PathVariable Long activityId,
        @Valid @RequestBody ActivityFeedbackService.Input input) { return feedback.submit(activityId, input); }
}

@RestController
@RequestMapping("/api/organizer/activities/{activityId}/feedback")
class OrganizerActivityFeedbackController {
    private final ActivityFeedbackService feedback;
    OrganizerActivityFeedbackController(ActivityFeedbackService feedback) { this.feedback = feedback; }
    @GetMapping List<ActivityFeedbackView> list(@PathVariable Long activityId) {
        return feedback.forOrganizer(activityId);
    }
    @GetMapping("/summary") FeedbackSummary summary(@PathVariable Long activityId) {
        return feedback.summary(activityId);
    }
}

@RestController
@RequestMapping("/api/admin")
class AdminActivityFeedbackController {
    record HideRequest(@NotBlank @Size(max = 300) String reason) {}
    private final ActivityFeedbackService feedback;
    AdminActivityFeedbackController(ActivityFeedbackService feedback) { this.feedback = feedback; }
    @GetMapping("/activities/{activityId}/feedback") List<ActivityFeedbackView> list(@PathVariable Long activityId) {
        return feedback.forAdmin(activityId);
    }
    @GetMapping("/activities/{activityId}/feedback/summary") FeedbackSummary summary(@PathVariable Long activityId) {
        return feedback.summary(activityId);
    }
    @PostMapping("/feedback/{id}/hide") ActivityFeedbackView hide(@PathVariable Long id,
        @Valid @RequestBody HideRequest request) { return feedback.hide(id, request.reason()); }
}

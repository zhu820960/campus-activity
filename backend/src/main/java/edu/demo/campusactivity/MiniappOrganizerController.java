package edu.demo.campusactivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/miniapp/organizer/activities")
class MiniappOrganizerController {
    record ManualRequest(@NotNull Long studentId, @NotBlank String reason) {}

    private final ActivityService activities;
    private final RegistrationService registrations;
    private final CheckinService checkins;
    private final ActivityFeedbackService feedback;
    private final OrganizerReviewHistory reviews;
    private final CurrentUser current;

    MiniappOrganizerController(ActivityService activities, RegistrationService registrations,
                               CheckinService checkins, ActivityFeedbackService feedback,
                               OrganizerReviewHistory reviews, CurrentUser current) {
        this.activities = activities;
        this.registrations = registrations;
        this.checkins = checkins;
        this.feedback = feedback;
        this.reviews = reviews;
        this.current = current;
    }

    void requireOrganizer() {
        UserAccount user = current.require(UserAccount.Role.STUDENT);
        if (!user.roles.contains(UserAccount.Role.ORGANIZER) || user.roles.contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "仅学生组织者可使用小程序活动管理");
        }
    }

    @GetMapping
    List<ActivityView> mine() {
        requireOrganizer();
        return activities.mine();
    }

    @GetMapping("/{id}")
    ActivityView detail(@PathVariable Long id) {
        requireOrganizer();
        return activities.ownedView(id);
    }

    @PostMapping
    ActivityView create(@Valid @RequestBody ActivityService.ActivityInput request) {
        requireOrganizer();
        return activities.create(request);
    }

    @PutMapping("/{id}")
    ActivityView edit(@PathVariable Long id, @Valid @RequestBody ActivityService.ActivityInput request) {
        requireOrganizer();
        return activities.edit(id, request);
    }

    @PostMapping("/{id}/submit")
    ActivityView submit(@PathVariable Long id) {
        requireOrganizer();
        return activities.submit(id);
    }

    @GetMapping("/{id}/registrations")
    List<RegistrationView> registrations(@PathVariable Long id) {
        requireOrganizer();
        return registrations.forActivity(id);
    }

    @GetMapping("/{id}/stats")
    StatsView stats(@PathVariable Long id) {
        requireOrganizer();
        return checkins.stats(id);
    }

    @GetMapping("/{id}/feedback")
    List<ActivityFeedbackView> feedback(@PathVariable Long id) {
        requireOrganizer();
        return feedback.forOrganizer(id);
    }

    @GetMapping("/{id}/feedback/summary")
    FeedbackSummary feedbackSummary(@PathVariable Long id) {
        requireOrganizer();
        return feedback.summary(id);
    }

    @GetMapping("/{id}/reviews")
    List<ActivityAuditView> reviews(@PathVariable Long id) {
        requireOrganizer();
        return reviews.list(id);
    }

    @PostMapping("/{id}/checkin-token")
    Map<String, Object> token(@PathVariable Long id) {
        requireOrganizer();
        return checkins.issueToken(id);
    }

    @PostMapping("/{id}/manual-checkin")
    RegistrationView manual(@PathVariable Long id, @Valid @RequestBody ManualRequest request) {
        requireOrganizer();
        return checkins.manual(id, request.studentId(), request.reason());
    }
}

@RestController
@RequestMapping("/api/miniapp/organizer/ai")
class MiniappOrganizerAiController {
    private final MiniappOrganizerController organizer;
    private final AiDraftController drafts;

    MiniappOrganizerAiController(MiniappOrganizerController organizer, AiDraftController drafts) {
        this.organizer = organizer;
        this.drafts = drafts;
    }

    @PostMapping("/draft")
    Map<String, String> draft(@Valid @RequestBody AiDraftController.DraftRequest request) {
        organizer.requireOrganizer();
        return drafts.draft(request);
    }
}

package edu.demo.campusactivity;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer/activities/{activityId}/reviews")
class OrganizerReviewHistory {
    private final CurrentUser current;
    private final ActivityService activities;
    private final ActivityAuditRepository audits;

    OrganizerReviewHistory(CurrentUser current, ActivityService activities, ActivityAuditRepository audits) {
        this.current = current;
        this.activities = activities;
        this.audits = audits;
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<ActivityAuditView> list(@PathVariable Long activityId) {
        current.require(UserAccount.Role.ORGANIZER);
        activities.owned(activityId);
        return audits.findByActivityIdOrderByIdDesc(activityId).stream()
            .filter(event -> event.action == ActivityAudit.Action.REVIEW_APPROVED
                || event.action == ActivityAudit.Action.REVIEW_REJECTED)
            .map(ActivityAuditView::of).toList();
    }
}

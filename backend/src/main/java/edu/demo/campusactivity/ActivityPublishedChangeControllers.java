package edu.demo.campusactivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/miniapp/organizer/activities/{id}/changes", "/api/organizer/activities/{id}/changes"})
class MiniappActivityChangeController {
    record Request(@Valid @NotNull ActivityService.ActivityInput proposed,
                   @NotBlank @Size(max = 500) String reason) {}
    private final ActivityPublishedChangeService changes;

    MiniappActivityChangeController(ActivityPublishedChangeService changes) { this.changes = changes; }

    @GetMapping
    ActivityPublishedChangeService.ChangeView pending(@PathVariable Long id) {
        return changes.pendingForOrganizer(id);
    }

    @PostMapping
    ActivityPublishedChangeService.ChangeView request(@PathVariable Long id,
                                                       @Valid @RequestBody Request input) {
        return changes.request(id, input.proposed(), input.reason());
    }
}

@RestController
@RequestMapping("/api/admin/activity-changes")
class AdminActivityChangeController {
    record ReviewRequest(boolean approved, String note) {}
    private final ActivityPublishedChangeService changes;

    AdminActivityChangeController(ActivityPublishedChangeService changes) { this.changes = changes; }

    @GetMapping("/pending")
    List<ActivityPublishedChangeService.ChangeView> pending() { return changes.pendingForAdmin(); }

    @PostMapping("/{id}/review")
    ActivityPublishedChangeService.ChangeView review(@PathVariable Long id,
                                                     @RequestBody ReviewRequest input) {
        return changes.review(id, input.approved(), input.note());
    }
}

@RestController
@RequestMapping("/api/admin/activities/{id}/published-change")
class AdminPublishedChangeController {
    record Request(@Valid @NotNull ActivityService.ActivityInput proposed,
                   @NotBlank @Size(max = 500) String reason) {}
    private final ActivityPublishedChangeService changes;

    AdminPublishedChangeController(ActivityPublishedChangeService changes) { this.changes = changes; }

    @PostMapping
    ActivityView direct(@PathVariable Long id, @Valid @RequestBody Request input) {
        return changes.direct(id, input.proposed(), input.reason());
    }
}

package edu.demo.campusactivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/miniapp/organizer/activities/{id}/cancellation", "/api/organizer/activities/{id}/cancellation"})
class MiniappActivityCancellationController {
    record Request(@NotBlank @Size(max = 500) String reason) {}
    private final ActivityCancellationService cancellations;

    MiniappActivityCancellationController(ActivityCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @GetMapping
    ActivityCancellationService.RequestView pending(@PathVariable Long id) {
        return cancellations.pendingForOrganizer(id);
    }

    @PostMapping
    ActivityCancellationService.RequestView request(@PathVariable Long id,
                                                     @Valid @RequestBody Request input) {
        return cancellations.request(id, input.reason());
    }
}

@RestController
@RequestMapping("/api/admin/activity-cancellations")
class AdminActivityCancellationController {
    record ReviewRequest(boolean approved, String note) {}
    private final ActivityCancellationService cancellations;

    AdminActivityCancellationController(ActivityCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @GetMapping("/pending")
    List<ActivityCancellationService.RequestView> pending() { return cancellations.pendingForAdmin(); }

    @GetMapping("/{id}")
    ActivityCancellationService.RequestView detail(@PathVariable Long id) {
        return cancellations.forAdmin(id);
    }

    @PostMapping("/{id}/review")
    ActivityCancellationService.RequestView review(@PathVariable Long id,
                                                    @RequestBody ReviewRequest request) {
        return cancellations.review(id, request.approved(), request.note());
    }
}

@RestController
@RequestMapping("/api/admin/activities/{id}/cancellation")
class AdminEmergencyCancellationController {
    record Request(@NotBlank @Size(max = 500) String reason) {}
    private final ActivityCancellationService cancellations;

    AdminEmergencyCancellationController(ActivityCancellationService cancellations) {
        this.cancellations = cancellations;
    }

    @PostMapping
    ActivityView cancel(@PathVariable Long id, @Valid @RequestBody Request request) {
        return cancellations.emergency(id, request.reason());
    }

    @GetMapping("/audit")
    List<ActivityCancellationService.MutationView> audit(@PathVariable Long id) {
        return cancellations.mutations(id);
    }
}

package edu.demo.campusactivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/miniapp/auth/appeals")
class MiniappBindingAppealController {
    record ChallengeRequest(@NotBlank String code) {}
    record SubmitRequest(@NotNull BindingAppeal.Type type, @NotBlank String proposedStudentNumber,
                         @NotBlank @Size(max = 1000) String reason) {}

    private final WechatCodeExchange wechat;
    private final BindingAppealService appeals;

    MiniappBindingAppealController(WechatCodeExchange wechat, BindingAppealService appeals) {
        this.wechat = wechat;
        this.appeals = appeals;
    }

    @GetMapping("/availability")
    java.util.Map<String, Boolean> availability() {
        return java.util.Map.of("enabled", appeals.enabled());
    }

    @PostMapping("/challenge")
    BindingAppealService.ChallengeResult challenge(@Valid @RequestBody ChallengeRequest request) {
        appeals.ensureEnabled();
        return appeals.challenge(wechat.exchange(request.code()));
    }

    @PostMapping("/status")
    BindingAppealService.StatusView status(@Valid @RequestBody ChallengeRequest request) {
        appeals.ensureEnabled();
        return appeals.latestForWechat(wechat.exchange(request.code()));
    }

    @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    void evidence(@RequestHeader("X-Appeal-Challenge") String challenge,
                  @RequestParam("file") MultipartFile file) {
        appeals.upload(challenge, file);
    }

    @PostMapping
    BindingAppealService.AppealView submit(@RequestHeader("X-Appeal-Challenge") String challenge,
                                            @Valid @RequestBody SubmitRequest request) {
        return appeals.submit(challenge, request.type(), request.proposedStudentNumber(), request.reason());
    }
}

@RestController
@RequestMapping("/api/admin/binding-appeals")
class AdminBindingAppealController {
    record RejectRequest(@NotBlank @Size(max = 1000) String reason) {}
    record DisputeRequest(@NotBlank @Size(max = 500) String reason) {}
    record AttributionDecisionRequest(@NotNull Long registrationId,
                                      @NotNull ParticipationAttributionReview.Decision decision,
                                      @NotBlank @Size(max = 1000) String reason) {}
    record ApproveRequest(@NotBlank @Size(max = 80) String verifiedName,
                          @NotBlank @Size(max = 1000) String reason,
                          boolean replaceTargetBinding,
                          @NotNull List<@Valid AttributionDecisionRequest> decisions) {}

    private final BindingAppealService appeals;
    private final CurrentUser current;

    AdminBindingAppealController(BindingAppealService appeals, CurrentUser current) {
        this.appeals = appeals;
        this.current = current;
    }

    @GetMapping("/availability")
    java.util.Map<String, Boolean> availability() {
        current.require(UserAccount.Role.ADMIN);
        return java.util.Map.of("enabled", appeals.enabled());
    }

    @GetMapping("/pending")
    List<BindingAppealService.AppealView> pending() {
        current.require(UserAccount.Role.ADMIN);
        return appeals.pending();
    }

    @GetMapping
    AuditPage<BindingAppealService.ReviewView> search(
        @RequestParam(defaultValue = "PENDING") BindingAppealService.ReviewQueue queue,
        @RequestParam(required = false) String studentNumber,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        current.require(UserAccount.Role.ADMIN);
        return appeals.search(queue, studentNumber, page, size);
    }

    @GetMapping("/{id}/evidence")
    ResponseEntity<Resource> evidence(@PathVariable Long id) {
        current.require(UserAccount.Role.ADMIN);
        Resource resource = appeals.evidence(id);
        MediaType type = resource.getFilename().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type)
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .body(resource);
    }

    @GetMapping("/{id}")
    BindingAppealService.ReviewView review(@PathVariable Long id) {
        current.require(UserAccount.Role.ADMIN);
        return appeals.review(id);
    }

    @GetMapping("/{id}/impact")
    BindingAppealService.ImpactView impact(@PathVariable Long id) {
        current.require(UserAccount.Role.ADMIN);
        return appeals.impact(id);
    }

    @PostMapping("/{id}/reject")
    BindingAppealService.AppealView reject(@PathVariable Long id, @Valid @RequestBody RejectRequest request) {
        return appeals.reject(id, current.require(UserAccount.Role.ADMIN), request.reason());
    }

    @PostMapping("/{id}/dispute")
    BindingAppealService.AppealView dispute(@PathVariable Long id, @Valid @RequestBody DisputeRequest request) {
        return appeals.dispute(id, current.require(UserAccount.Role.ADMIN), request.reason());
    }

    @PostMapping("/{id}/approve")
    BindingAppealService.AppealView approve(@PathVariable Long id, @Valid @RequestBody ApproveRequest request) {
        return appeals.approve(id, current.require(UserAccount.Role.ADMIN), request);
    }

    @GetMapping("/{id}/attribution-reviews")
    List<BindingAppealService.AttributionReviewView> attributionReviews(@PathVariable Long id) {
        current.require(UserAccount.Role.ADMIN);
        return appeals.attributionReviews(id);
    }
}

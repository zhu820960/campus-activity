package edu.demo.campusactivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/miniapp/auth")
class MiniappAuthController {
    record CodeRequest(@NotBlank String code) {}
    record BindRequest(@NotBlank String code, @Email @NotBlank String email,
                       @NotBlank @Size(min = 8) String password) {}
    record StudentBindRequest(@NotBlank String code, @NotBlank String studentNumber,
                              @NotBlank String name) {}
    record WebCredentialsRequest(@NotBlank String code, @NotBlank String password) {}

    private final WechatCodeExchange wechat;
    private final MiniappAuthService auth;
    private final CurrentUser current;

    MiniappAuthController(WechatCodeExchange wechat, MiniappAuthService auth, CurrentUser current) {
        this.wechat = wechat;
        this.auth = auth;
        this.current = current;
    }

    @PostMapping("/login")
    MiniappAuthService.LoginResult login(@Valid @RequestBody CodeRequest request) {
        return auth.login(wechat.exchange(request.code()));
    }

    @PostMapping("/bind")
    MiniappAuthService.LoginResult bind(@Valid @RequestBody BindRequest request) {
        return auth.bind(wechat.exchange(request.code()), request.email(), request.password());
    }

    @PostMapping("/bind-student")
    MiniappAuthService.LoginResult bindStudent(@Valid @RequestBody StudentBindRequest request) {
        return auth.bindStudent(wechat.exchange(request.code()), request.studentNumber(), request.name());
    }

    @GetMapping("/me")
    MiniappAuthService.StudentView me() {
        return MiniappAuthService.StudentView.of(current.require(UserAccount.Role.STUDENT));
    }

    @PostMapping("/web-credentials")
    MiniappAuthService.StudentView activateWebCredentials(@Valid @RequestBody WebCredentialsRequest request) {
        Long studentId = current.require(UserAccount.Role.STUDENT).id;
        return auth.activateWebCredentials(studentId, wechat.exchange(request.code()), request.password());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader("Authorization") String authorization) {
        auth.revoke(authorization.substring(7));
    }
}

@RestController
@RequestMapping("/api/miniapp")
class MiniappStudentController {
    record ScanRequest(@NotBlank String token) {}

    private final RegistrationService registrations;
    private final CheckinService checkins;
    private final ActivityService activities;
    private final AnnouncementRepository announcements;
    private final CurrentUser current;

    MiniappStudentController(RegistrationService registrations, CheckinService checkins,
                             ActivityService activities, AnnouncementRepository announcements,
                             CurrentUser current) {
        this.registrations = registrations;
        this.checkins = checkins;
        this.activities = activities;
        this.announcements = announcements;
        this.current = current;
    }

    @GetMapping("/activities")
    List<ActivityView> activities() {
        current.require(UserAccount.Role.STUDENT);
        return activities.published();
    }

    @GetMapping("/activities/{id}")
    ActivityView activity(@PathVariable Long id) {
        current.require(UserAccount.Role.STUDENT);
        return activities.visible(id);
    }

    @GetMapping("/announcements")
    List<AnnouncementView> announcements() {
        current.require(UserAccount.Role.STUDENT);
        return announcements.findByActiveTrueOrderByPublishedAtDesc().stream()
            .map(AnnouncementView::of).toList();
    }

    @GetMapping("/my/registrations")
    List<RegistrationView> mine() { return registrations.mine(); }

    @PostMapping("/activities/{id}/registrations")
    RegistrationView register(@PathVariable Long id) { return registrations.register(id); }

    @DeleteMapping("/activities/{id}/registrations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable Long id) { registrations.cancel(id); }

    @PostMapping("/activities/{id}/checkins")
    RegistrationView checkin(@PathVariable Long id, @Valid @RequestBody ScanRequest request) {
        return checkins.scan(id, request.token());
    }
}

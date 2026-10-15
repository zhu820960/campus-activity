package edu.demo.campusactivity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/activities")
class ActivityController {
    record ScanRequest(@NotBlank String token) {}

    private final ActivityService activities;
    private final RegistrationService registrations;
    private final CheckinService checkins;
    private final CurrentUser current;

    ActivityController(ActivityService activities, RegistrationService registrations,
                       CheckinService checkins, CurrentUser current) {
        this.activities = activities;
        this.registrations = registrations;
        this.checkins = checkins;
        this.current = current;
    }

    @GetMapping
    List<ActivityView> published() {
        current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        return activities.published();
    }

    @GetMapping("/{id}")
    ActivityView detail(@PathVariable Long id) {
        current.require(UserAccount.Role.ORGANIZER, UserAccount.Role.ADMIN);
        return activities.visible(id);
    }

    @PostMapping("/{id}/registrations")
    RegistrationView register(@PathVariable Long id) { return registrations.register(id); }

    @DeleteMapping("/{id}/registrations")
    void cancel(@PathVariable Long id) { registrations.cancel(id); }

    @PostMapping("/{id}/checkins")
    RegistrationView scan(@PathVariable Long id, @Valid @RequestBody ScanRequest request) {
        return checkins.scan(id, request.token());
    }
}

@RestController
@RequestMapping("/api/my")
class MyController {
    private final RegistrationService registrations;

    MyController(RegistrationService registrations) { this.registrations = registrations; }

    @GetMapping("/registrations")
    List<RegistrationView> registrations() { return registrations.mine(); }
}

@RestController
@RequestMapping("/api/organizer/activities")
class OrganizerController {
    record ManualRequest(@NotNull Long studentId, @NotBlank String reason) {}

    private final ActivityService activities;
    private final RegistrationService registrations;
    private final CheckinService checkins;

    OrganizerController(ActivityService activities, RegistrationService registrations, CheckinService checkins) {
        this.activities = activities;
        this.registrations = registrations;
        this.checkins = checkins;
    }

    @GetMapping
    List<ActivityView> mine() { return activities.mine(); }

    @PostMapping
    ActivityView create(@Valid @RequestBody ActivityService.ActivityInput request) {
        return activities.create(request);
    }

    @PutMapping("/{id}")
    ActivityView edit(@PathVariable Long id, @Valid @RequestBody ActivityService.ActivityInput request) {
        return activities.edit(id, request);
    }

    @PostMapping("/{id}/submit")
    ActivityView submit(@PathVariable Long id) { return activities.submit(id); }

    @GetMapping("/{id}/registrations")
    List<RegistrationView> registrations(@PathVariable Long id) {
        return registrations.forActivity(id);
    }

    @PostMapping("/{id}/checkin-token")
    Map<String, Object> token(@PathVariable Long id) { return checkins.issueToken(id); }

    @PostMapping("/{id}/manual-checkin")
    RegistrationView manual(@PathVariable Long id, @Valid @RequestBody ManualRequest request) {
        return checkins.manual(id, request.studentId(), request.reason());
    }

    @GetMapping("/{id}/stats")
    StatsView stats(@PathVariable Long id) { return checkins.stats(id); }
}

@RestController
@RequestMapping("/api/admin")
class AdminController {
    record ReviewRequest(boolean approved, String note) {}
    record RoleRequest(boolean enabled) {}
    record UnbindRequest(@jakarta.validation.constraints.NotBlank
                         @jakarta.validation.constraints.Size(max = 500) String reason) {}
    record AdminUserView(Long id, String name, String email, String studentNumber,
                         java.util.Set<UserAccount.Role> roles, boolean wechatBound,
                         boolean webCredentialsActive, long activityCount,
                         boolean bindingManagementEnabled) {}

    private final ActivityService activities;
    private final RegistrationService registrations;
    private final CheckinService checkins;
    private final ActivityAuditRepository audits;
    private final UserSecurityAuditRepository securityAudits;
    private final UserMessageService messages;
    private final UserRepository users;
    private final WechatStudentBindingRepository bindings;
    private final MiniappAccessTokenRepository miniappTokens;
    private final ActivityRepository activityRepository;
    private final AdminMetrics metrics;
    private final CurrentUser current;
    private final String appId;
    private final boolean bindingManagementEnabled;

    AdminController(ActivityService activities, RegistrationService registrations, CheckinService checkins,
                    ActivityAuditRepository audits, UserSecurityAuditRepository securityAudits,
                    UserMessageService messages, UserRepository users, WechatStudentBindingRepository bindings,
                    MiniappAccessTokenRepository miniappTokens,
                    ActivityRepository activityRepository, AdminMetrics metrics, CurrentUser current,
                    @org.springframework.beans.factory.annotation.Value("${app.wechat.app-id:}") String appId,
                    @org.springframework.beans.factory.annotation.Value("${app.binding-management.enabled:false}")
                    boolean bindingManagementEnabled) {
        this.activities = activities;
        this.registrations = registrations;
        this.checkins = checkins;
        this.audits = audits;
        this.securityAudits = securityAudits;
        this.messages = messages;
        this.users = users;
        this.bindings = bindings;
        this.miniappTokens = miniappTokens;
        this.activityRepository = activityRepository;
        this.metrics = metrics;
        this.current = current;
        this.appId = appId;
        this.bindingManagementEnabled = bindingManagementEnabled;
    }

    @GetMapping("/activities/pending")
    List<ActivityView> pending() { return activities.pending(); }

    @GetMapping("/activities")
    List<AdminActivityOverview> allActivities() { return checkins.adminOverview(); }

    @GetMapping("/metrics/daily")
    List<DailyMetric> dailyMetrics(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "7") int days) {
        return metrics.daily(days);
    }

    @PostMapping("/activities")
    ActivityView create(@Valid @RequestBody ActivityService.ActivityInput request) {
        return activities.createForAdmin(request);
    }

    @PutMapping("/activities/{id}")
    ActivityView edit(@PathVariable Long id, @Valid @RequestBody ActivityService.ActivityInput request) {
        return activities.editOwnForAdmin(id, request);
    }

    @PostMapping("/activities/{id}/publish")
    ActivityView publish(@PathVariable Long id) { return activities.publishOwnForAdmin(id); }

    @GetMapping("/activities/{id}")
    ActivityView activity(@PathVariable Long id) { return activities.visibleToAdmin(id); }

    @GetMapping("/activities/{id}/registrations")
    List<RegistrationView> registrations(@PathVariable Long id) { return registrations.forAdmin(id); }

    @GetMapping("/activities/{id}/stats")
    StatsView stats(@PathVariable Long id) { return checkins.adminStats(id); }

    @GetMapping("/activities/{id}/audit")
    @Transactional(readOnly = true)
    List<ActivityAuditView> audit(@PathVariable Long id) {
        activities.visibleToAdmin(id);
        return audits.findByActivityIdOrderByIdDesc(id).stream().map(ActivityAuditView::of).toList();
    }

    @GetMapping("/activity-audits")
    @Transactional(readOnly = true)
    List<AdminAuditOverview> activityAudits() {
        current.require(UserAccount.Role.ADMIN);
        return audits.findTop100ByOrderByIdDesc().stream().map(AdminAuditOverview::of).toList();
    }

    @GetMapping("/user-audits")
    @Transactional(readOnly = true)
    List<UserSecurityAuditView> userAudits() {
        current.require(UserAccount.Role.ADMIN);
        return securityAudits.findTop100ByOrderByIdDesc().stream()
            .map(UserSecurityAuditView::of).toList();
    }

    @PostMapping("/activities/{id}/checkin-token")
    Map<String, Object> token(@PathVariable Long id) { return checkins.issueAdminToken(id); }

    @PostMapping("/activities/{id}/review")
    ActivityView review(@PathVariable Long id, @RequestBody ReviewRequest request) {
        return activities.review(id, request.approved(), request.note());
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    List<AdminUserView> users() {
        current.require(UserAccount.Role.ADMIN);
        return users.findAllByOrderByCreatedAtDesc().stream().filter(account -> !account.getRoles().isEmpty()).map(account -> new AdminUserView(
            account.id, account.name, account.email, account.studentNumber,
            java.util.Set.copyOf(account.roles),
            bindings.findByAppIdAndStudentIdAndUnboundAtIsNull(appId, account.id).isPresent(),
            account.roles.contains(UserAccount.Role.ORGANIZER) && account.passwordHash != null,
            activityRepository.countByOrganizerId(account.id), bindingManagementEnabled)).toList();
    }

    @PatchMapping("/users/{id}/wechat-binding")
    @Transactional
    AuthController.UserView unbindWechat(@PathVariable Long id, @Valid @RequestBody UnbindRequest request) {
        UserAccount actor = current.require(UserAccount.Role.ADMIN);
        if (!bindingManagementEnabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "微信解绑需完成申诉与历史归属流程后启用");
        }
        UserAccount student = users.lockUserById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!student.roles.contains(UserAccount.Role.STUDENT) || student.roles.contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "只能解绑普通学生账号");
        }
        WechatStudentBinding binding = bindings.lockActiveByStudent(appId, id)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "该学生账号当前未绑定微信"));
        java.time.Instant now = java.time.Instant.now();
        binding.unbind(actor, request.reason().trim(), now);
        miniappTokens.revokeByBinding(binding.getId(), now);
        student.revokeWebCredentials();
        securityAudits.save(new UserSecurityAudit(actor, student, UserSecurityAudit.Action.WECHAT_UNBOUND));
        return AuthController.UserView.of(student);
    }

    @PatchMapping("/users/{id}/organizer")
    @Transactional
    AuthController.UserView organizer(@PathVariable Long id, @RequestBody RoleRequest request) {
        UserAccount actor = current.require(UserAccount.Role.ADMIN);
        UserAccount user = users.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (user.roles.contains(UserAccount.Role.ADMIN)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "不能修改管理员身份");
        }
        if (!user.roles.contains(UserAccount.Role.STUDENT)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "只能为学生账号设置组织者权限");
        }
        if (request.enabled() && !user.roles.contains(UserAccount.Role.ORGANIZER)
            && (user.studentNumber == null || !user.studentNumber.matches("[0-9]{14}")
                || bindings.findByAppIdAndStudentIdAndUnboundAtIsNull(appId, user.id).isEmpty())) {
            throw new ApiException(HttpStatus.CONFLICT, "请先让学生在小程序绑定14位学号，再授予组织者权限");
        }
        boolean changed = request.enabled()
            ? user.roles.add(UserAccount.Role.ORGANIZER)
            : user.roles.remove(UserAccount.Role.ORGANIZER);
        if (changed) {
            UserSecurityAudit event = securityAudits.save(new UserSecurityAudit(actor, user, request.enabled()
                ? UserSecurityAudit.Action.ORGANIZER_GRANTED : UserSecurityAudit.Action.ORGANIZER_REVOKED));
            messages.send(user, null, request.enabled() ? UserMessage.Kind.ORGANIZER_GRANTED
                : UserMessage.Kind.ORGANIZER_REVOKED,
                request.enabled() ? "组织者权限已开通" : "组织者权限已撤销",
                request.enabled() ? "你现在可以在小程序管理活动，并用学号设置 Web 登录密码。" : "你的组织者 Web 管理权限已结束。",
                "role:" + event.id);
        }
        return AuthController.UserView.of(user);
    }
}

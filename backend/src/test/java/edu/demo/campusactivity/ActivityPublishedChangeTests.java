package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:published_changes;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp"
})
class ActivityPublishedChangeTests {
    @Autowired UserRepository users;
    @Autowired WechatIdentityRepository identities;
    @Autowired WechatStudentBindingRepository bindings;
    @Autowired ActivityService activities;
    @Autowired ActivityPublishedChangeService changes;
    @Autowired ActivityCancellationService cancellations;
    @Autowired ActivityRepository activityRows;
    @Autowired RegistrationService registrations;
    @Autowired UserMessageService messages;
    @Autowired ActivityMutationAuditRepository audits;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(UserAccount account) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(account.id, null, List.of()));
    }

    private void asMiniapp(WechatStudentBinding binding) {
        var authentication = new UsernamePasswordAuthenticationToken(binding.student.id, null, List.of());
        authentication.setDetails(new MiniappAttribution(binding.student.id,
            binding.identity.id, binding.id));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private UserAccount organizer(String suffix) {
        UserAccount user = users.saveAndFlush(new UserAccount("组织者" + suffix,
            "change-organizer-" + suffix + "@test.local", "unused", UserAccount.Role.ORGANIZER));
        user.roles.add(UserAccount.Role.STUDENT);
        return users.saveAndFlush(user);
    }

    private UserAccount admin(String suffix) {
        return users.saveAndFlush(new UserAccount("管理员" + suffix,
            "change-admin-" + suffix + "@test.local", "unused", UserAccount.Role.ADMIN));
    }

    private Long published(String suffix, UserAccount organizer, UserAccount admin) {
        Instant now = Instant.now();
        as(organizer);
        Long id = activities.create(new ActivityService.ActivityInput("变更测试" + suffix, "原说明", "原导语",
            null, "旧场地", 108.93, 34.34, now.plusSeconds(7200), now.plusSeconds(10800),
            now.minusSeconds(60), now.plusSeconds(3600), now.plusSeconds(7100), now.plusSeconds(9000), 20)).id();
        activities.submit(id);
        as(admin);
        activities.review(id, true, "");
        return id;
    }

    private ActivityService.ActivityInput proposal(Long id, String title, String location, int capacity) {
        ActivityView original = activities.visible(id);
        return new ActivityService.ActivityInput(title, original.description(), original.articleSummary(),
            original.coverImageUrl(), location, original.longitude(), original.latitude(),
            original.startsAt(), original.endsAt(), original.registrationOpensAt(),
            original.registrationClosesAt(), original.checkinOpensAt(), original.checkinClosesAt(), capacity);
    }

    @Test
    void reviewKeepsOldVersionUntilApprovalAndNotifiesAttributedRegistrants() {
        UserAccount owner = organizer("approval");
        UserAccount admin = admin("approval");
        UserAccount student = users.saveAndFlush(new UserAccount("参与学生", "change-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        WechatIdentity identity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "change-wechat"));
        WechatStudentBinding binding = bindings.saveAndFlush(new WechatStudentBinding(identity, student));
        Long id = published("approval", owner, admin);
        asMiniapp(binding);
        registrations.register(id);
        as(owner);
        var pending = changes.request(id, proposal(id, "更新后的标题", "新场地", 20), "场地调整");
        assertEquals(ActivityChangeRequest.Status.PENDING, pending.status());
        UserAccount lateStudent = users.saveAndFlush(new UserAccount("后报名学生", "change-late@test.local",
            "unused", UserAccount.Role.STUDENT));
        WechatIdentity lateIdentity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "change-late-wechat"));
        WechatStudentBinding lateBinding = bindings.saveAndFlush(new WechatStudentBinding(lateIdentity, lateStudent));
        asMiniapp(lateBinding);
        registrations.register(id);
        asMiniapp(binding);
        assertEquals("旧场地", activities.visible(id).location());
        as(admin);
        assertEquals(1, changes.pendingForAdmin().stream().filter(row -> row.id().equals(pending.id())).count());
        assertTrue(cancellations.pendingForAdmin().stream().noneMatch(row -> row.id().equals(pending.id())));
        assertEquals(404, assertThrows(ApiException.class,
            () -> cancellations.forAdmin(pending.id())).status.value());
        assertEquals(ActivityChangeRequest.Status.APPROVED,
            changes.review(pending.id(), true, "已核对场地").status());
        assertEquals("新场地", activities.visibleToAdmin(id).location());
        assertEquals(Activity.Status.PUBLISHED, activities.visibleToAdmin(id).status());
        assertEquals("CHANGE_APPROVED", audits.findByActivityIdOrderByIdDesc(id).get(0).action);
        asMiniapp(binding);
        assertTrue(messages.mine().stream().anyMatch(row -> row.kind() == UserMessage.Kind.ACTIVITY_UPDATED));
        asMiniapp(lateBinding);
        assertTrue(messages.mine().stream().anyMatch(row -> row.kind() == UserMessage.Kind.ACTIVITY_UPDATED));
        as(admin);
        assertEquals(409, assertThrows(ApiException.class,
            () -> changes.review(pending.id(), true, "重复批准")).status.value());
    }

    @Test
    void directChangeNeedsReasonAndCannotUndercutRegistrationsOrOverridePendingRequest() {
        UserAccount owner = organizer("direct");
        UserAccount admin = admin("direct");
        UserAccount student = users.saveAndFlush(new UserAccount("学生", "change-student-direct@test.local",
            "unused", UserAccount.Role.STUDENT));
        WechatIdentity identity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "direct-wechat"));
        WechatStudentBinding binding = bindings.saveAndFlush(new WechatStudentBinding(identity, student));
        Long id = published("direct", owner, admin);
        asMiniapp(binding);
        registrations.register(id);
        UserAccount secondStudent = users.saveAndFlush(new UserAccount("第二名学生",
            "change-student-direct-2@test.local", "unused", UserAccount.Role.STUDENT));
        WechatIdentity secondIdentity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "direct-wechat-2"));
        asMiniapp(bindings.saveAndFlush(new WechatStudentBinding(secondIdentity, secondStudent)));
        registrations.register(id);
        as(admin);
        assertEquals(400, assertThrows(ApiException.class,
            () -> changes.direct(id, proposal(id, "新标题", "旧场地", 20), " ")).status.value());
        assertEquals(409, assertThrows(ApiException.class,
            () -> changes.direct(id, proposal(id, "新标题", "旧场地", 1), "调整名额")).status.value());
        as(owner);
        var pending = changes.request(id, proposal(id, "候选标题", "旧场地", 20), "修改导语");
        as(admin);
        assertEquals(409, assertThrows(ApiException.class,
            () -> changes.direct(id, proposal(id, "管理员标题", "旧场地", 20), "紧急调整")).status.value());
        changes.review(pending.id(), false, "请补充说明");
        assertEquals("管理员标题", changes.direct(id,
            proposal(id, "管理员标题", "旧场地", 20), "紧急调整").title());
        assertEquals("CHANGE_DIRECT", audits.findByActivityIdOrderByIdDesc(id).get(0).action);
    }

    @Test
    void staleRequestAndOtherOrganizerCannotChangePublishedActivity() {
        UserAccount owner = organizer("owner");
        UserAccount other = organizer("other");
        UserAccount admin = admin("stale");
        Long id = published("stale", owner, admin);
        as(other);
        assertEquals(403, assertThrows(ApiException.class,
            () -> changes.request(id, proposal(id, "越权", "旧场地", 20), "越权")).status.value());
        as(owner);
        var pending = changes.request(id, proposal(id, "候选标题", "旧场地", 20), "改标题");
        Activity activity = activityRows.findById(id).orElseThrow();
        activity.location = "管理员已调整";
        activityRows.saveAndFlush(activity);
        as(admin);
        assertEquals(409, assertThrows(ApiException.class,
            () -> changes.review(pending.id(), true, "同意")).status.value());
        assertEquals(ActivityChangeRequest.Status.REJECTED,
            changes.review(pending.id(), false, "版本已变更").status());
    }

    @Test
    void startedActivityOnlyAcceptsContentChanges() {
        UserAccount owner = organizer("started");
        UserAccount admin = admin("started");
        Long id = published("started", owner, admin);
        Activity activity = activityRows.findById(id).orElseThrow();
        activity.startsAt = Instant.now().minusSeconds(60);
        activity.endsAt = Instant.now().plusSeconds(3600);
        activity.registrationClosesAt = Instant.now().minusSeconds(120);
        activity.checkinOpensAt = Instant.now().minusSeconds(90);
        activity.checkinClosesAt = Instant.now().plusSeconds(1800);
        activityRows.saveAndFlush(activity);
        as(admin);
        assertEquals(409, assertThrows(ApiException.class,
            () -> changes.direct(id, proposal(id, "新标题", "新场地", 20), "变更地点")).status.value());
        assertEquals("更新文案", changes.direct(id,
            proposal(id, "更新文案", "旧场地", 20), "勘误").title());
    }
}

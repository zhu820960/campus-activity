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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:activity_cancellation;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp"
})
class ActivityCancellationTests {
    @Autowired UserRepository users;
    @Autowired WechatIdentityRepository identities;
    @Autowired WechatStudentBindingRepository bindings;
    @Autowired ActivityService activities;
    @Autowired ActivityRepository activityRows;
    @Autowired ActivityCancellationService cancellations;
    @Autowired RegistrationService registrations;
    @Autowired RegistrationRepository registrationRows;
    @Autowired CheckinService checkins;
    @Autowired UserMessageService messages;
    @Autowired ActivityChangeRequestRepository requests;
    @Autowired ActivityMutationAuditRepository mutations;

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

    private Long publishedActivity(String suffix, UserAccount organizer, UserAccount admin) {
        Instant now = Instant.now();
        as(organizer);
        Long id = activities.create(new ActivityService.ActivityInput("取消测试" + suffix, "说明", "导语",
            null, "报告厅", 108.93, 34.34, now.plusSeconds(7200), now.plusSeconds(10800),
            now.minusSeconds(60), now.plusSeconds(3600), now.plusSeconds(7100),
            now.plusSeconds(9000), 20)).id();
        activities.submit(id);
        as(admin);
        activities.review(id, true, "");
        return id;
    }

    private UserAccount organizer(String suffix) {
        UserAccount organizer = users.saveAndFlush(new UserAccount("组织者" + suffix,
            "cancel-organizer-" + suffix + "@test.local", "unused", UserAccount.Role.ORGANIZER));
        organizer.roles.add(UserAccount.Role.STUDENT);
        return users.saveAndFlush(organizer);
    }

    private UserAccount admin(String suffix) {
        return users.saveAndFlush(new UserAccount("管理员" + suffix,
            "cancel-admin-" + suffix + "@test.local", "unused", UserAccount.Role.ADMIN));
    }

    @Test
    void approvalCancelsOnlyAfterReviewAndNotifiesAttributedParticipants() {
        UserAccount organizer = organizer("approval");
        UserAccount admin = admin("approval");
        UserAccount student = users.saveAndFlush(new UserAccount("学生", "cancel-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        WechatIdentity identity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "cancel-wechat"));
        WechatStudentBinding binding = bindings.saveAndFlush(new WechatStudentBinding(identity, student));
        Long id = publishedActivity("approval", organizer, admin);
        asMiniapp(binding);
        registrations.register(id);
        UserAccount legacyStudent = users.saveAndFlush(new UserAccount("旧数据学生", "cancel-legacy@test.local",
            "unused", UserAccount.Role.STUDENT));
        registrationRows.saveAndFlush(new Registration(activities.find(id), legacyStudent));
        as(organizer);
        var pending = cancellations.request(id, "场地突发不可用");
        assertEquals(ActivityChangeRequest.Status.PENDING, pending.status());
        assertEquals(id, pending.activityId());
        UserAccount lateStudent = users.saveAndFlush(new UserAccount("后报名学生", "cancel-late@test.local",
            "unused", UserAccount.Role.STUDENT));
        WechatIdentity lateIdentity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "late-wechat"));
        WechatStudentBinding lateBinding = bindings.saveAndFlush(new WechatStudentBinding(lateIdentity, lateStudent));
        asMiniapp(lateBinding);
        registrations.register(id);
        asMiniapp(binding);
        assertEquals(Activity.Status.PUBLISHED, activities.visible(id).status());
        as(admin);
        assertEquals(1, cancellations.pendingForAdmin().stream()
            .filter(request -> request.id().equals(pending.id())).count());
        assertEquals(Activity.Status.PUBLISHED, activities.visibleToAdmin(id).status());
        assertEquals(ActivityChangeRequest.Status.APPROVED,
            cancellations.review(pending.id(), true, "已联系场地").status());
        assertEquals(Activity.Status.CANCELLED, activities.visibleToAdmin(id).status());
        assertEquals("场地突发不可用", activities.visibleToAdmin(id).cancelReason());
        assertEquals(1, mutations.findByActivityIdOrderByIdDesc(id).size());
        assertTrue(cancellations.mutations(id).get(0).beforeSnapshot().contains("PUBLISHED"));
        assertTrue(cancellations.mutations(id).get(0).afterSnapshot().contains("CANCELLED"));
        asMiniapp(binding);
        assertEquals(Activity.Status.CANCELLED, activities.visible(id).status());
        assertTrue(messages.mine().stream().anyMatch(message -> message.kind() == UserMessage.Kind.ACTIVITY_CANCELLED));
        assertEquals(409, assertThrows(ApiException.class, () -> registrations.cancel(id)).status.value());
        asMiniapp(lateBinding);
        assertTrue(messages.mine().stream().anyMatch(message -> message.kind() == UserMessage.Kind.ACTIVITY_CANCELLED));
        binding.unboundAt = Instant.now();
        bindings.saveAndFlush(binding);
        WechatIdentity otherIdentity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "other-wechat"));
        WechatStudentBinding otherBinding = bindings.saveAndFlush(new WechatStudentBinding(otherIdentity, student));
        asMiniapp(otherBinding);
        assertTrue(messages.mine().stream().noneMatch(message -> message.kind() == UserMessage.Kind.ACTIVITY_CANCELLED));
        assertEquals(409, assertThrows(ApiException.class, () -> registrations.register(id)).status.value());
        assertEquals(404, assertThrows(ApiException.class, () -> registrations.cancel(id)).status.value());
        assertEquals(409, assertThrows(ApiException.class, () -> checkins.scan(id, "unused")).status.value());
        as(admin);
        assertEquals(409, assertThrows(ApiException.class,
            () -> cancellations.review(pending.id(), true, "again")).status.value());
    }

    @Test
    void rejectionKeepsPublishedAndEmergencyCancellationClosesPendingRequest() {
        UserAccount organizer = organizer("emergency");
        UserAccount admin = admin("emergency");
        Long id = publishedActivity("emergency", organizer, admin);
        as(organizer);
        var first = cancellations.request(id, "日程冲突");
        assertEquals(id, first.activityId());
        assertEquals(409, assertThrows(ApiException.class,
            () -> cancellations.request(id, "再次申请")).status.value());
        as(admin);
        assertEquals(400, assertThrows(ApiException.class,
            () -> cancellations.review(first.id(), false, " ")).status.value());
        cancellations.review(first.id(), false, "按原计划举办");
        assertEquals(Activity.Status.PUBLISHED, activities.visibleToAdmin(id).status());
        as(organizer);
        var second = cancellations.request(id, "场地关闭");
        as(admin);
        assertEquals(Activity.Status.CANCELLED, cancellations.emergency(id, "安全原因紧急取消").status());
        assertEquals(ActivityChangeRequest.Status.REJECTED, requests.findById(second.id()).orElseThrow().status);
        assertEquals("CANCEL_EMERGENCY", cancellations.mutations(id).get(0).action());
        assertFalse(cancellations.pendingForAdmin().stream().anyMatch(row -> row.activityId().equals(id)));
    }

    @Test
    void studentsAndOtherOrganizersCannotRequestCancellation() {
        UserAccount owner = organizer("owner");
        UserAccount other = organizer("other");
        UserAccount admin = admin("roles");
        UserAccount student = users.saveAndFlush(new UserAccount("普通学生", "cancel-plain@test.local",
            "unused", UserAccount.Role.STUDENT));
        Long id = publishedActivity("roles", owner, admin);
        as(student);
        assertEquals(403, assertThrows(ApiException.class,
            () -> cancellations.request(id, "取消")).status.value());
        as(other);
        assertEquals(403, assertThrows(ApiException.class,
            () -> cancellations.request(id, "取消")).status.value());
        as(owner);
        assertEquals(403, assertThrows(ApiException.class,
            () -> cancellations.emergency(id, "越权")).status.value());
    }

    @Test
    void materialChangeAfterRequestRequiresOrganizerToResubmit() {
        UserAccount organizer = organizer("stale");
        UserAccount admin = admin("stale");
        Long id = publishedActivity("stale", organizer, admin);
        as(organizer);
        var pending = cancellations.request(id, "原场地不可用");
        Activity activity = activityRows.findById(id).orElseThrow();
        activity.location = "新的活动场地";
        activityRows.saveAndFlush(activity);
        as(admin);
        assertEquals(409, assertThrows(ApiException.class,
            () -> cancellations.review(pending.id(), true, "同意")).status.value());
        assertEquals(Activity.Status.PUBLISHED, activities.visibleToAdmin(id).status());
        assertEquals(ActivityChangeRequest.Status.REJECTED,
            cancellations.review(pending.id(), false, "场地已变更，请重新提出申请").status());
    }
}

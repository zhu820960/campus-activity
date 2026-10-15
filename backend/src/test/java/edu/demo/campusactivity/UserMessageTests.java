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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:user_messages;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp"
})
class UserMessageTests {
    @Autowired UserRepository users;
    @Autowired ActivityService activities;
    @Autowired RegistrationService registrations;
    @Autowired UserMessageService messages;
    @Autowired UserMessageRepository repository;
    @Autowired WechatIdentityRepository identities;
    @Autowired WechatStudentBindingRepository bindings;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(UserAccount account) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(account.id, null, List.of()));
    }

    private WechatStudentBinding bind(UserAccount account, String openId) {
        return bindings.saveAndFlush(new WechatStudentBinding(
            identities.saveAndFlush(new WechatIdentity("test-miniapp", openId)), account));
    }

    private void asMiniapp(WechatStudentBinding binding) {
        var authentication = new UsernamePasswordAuthenticationToken(binding.student.id, null, List.of());
        authentication.setDetails(new MiniappAttribution(binding.student.id,
            binding.identity.id, binding.id));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void reviewAndRegistrationMessagesRemainPrivateAndReadOnce() {
        long initialMessageCount = repository.count();
        UserAccount organizer = users.saveAndFlush(new UserAccount("组织者", "msg-organizer@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "msg-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        UserAccount student = users.saveAndFlush(new UserAccount("学生", "msg-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        bind(organizer, "msg-organizer");
        WechatStudentBinding studentBinding = bind(student, "msg-student");
        Instant now = Instant.now();
        ActivityService.ActivityInput input = new ActivityService.ActivityInput("消息测试活动", "正文", "摘要",
            null, "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(60), now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 20);

        as(organizer);
        Long activityId = activities.create(input).id();
        activities.submit(activityId);
        as(admin);
        activities.review(activityId, true, "");
        assertEquals(1, repository.countByIdentityIdAndReadAtIsNull(
            bindings.findActiveIdentityId("test-miniapp", organizer.id).orElseThrow()));
        asMiniapp(studentBinding);
        registrations.register(activityId);
        assertEquals(1, messages.unread());
        UserMessageView studentMessage = messages.mine().get(0);
        assertEquals(UserMessage.Kind.REGISTRATION_SUCCESS, studentMessage.kind());
        assertEquals(activityId, studentMessage.activityId());
        assertNull(studentMessage.readAt());
        assertEquals(404, assertThrows(ApiException.class,
            () -> messages.read(repository.findTop100ByIdentityIdOrderByCreatedAtDesc(
                bindings.findActiveIdentityId("test-miniapp", organizer.id).orElseThrow()).get(0).id))
            .status.value());
        assertNotNull(messages.read(studentMessage.id()).readAt());
        assertEquals(0, messages.unread());
        assertNotNull(messages.read(studentMessage.id()).readAt());
        assertEquals(initialMessageCount + 2, repository.count());
    }

    @Test
    void newBindingCannotReadPreviousIdentityOrUnattributedMessages() {
        UserAccount student = users.saveAndFlush(new UserAccount("混绑学生", "msg-transfer@test.local",
            "unused", UserAccount.Role.STUDENT));
        WechatStudentBinding oldBinding = bind(student, "msg-old-wechat");
        messages.send(student, null, UserMessage.Kind.ORGANIZER_GRANTED,
            "旧身份消息", "旧身份私有内容", "msg-old-event");
        Long oldMessageId = repository.findTop100ByIdentityIdOrderByCreatedAtDesc(
            oldBinding.identity.id).get(0).id;
        oldBinding.unboundAt = Instant.now();
        bindings.saveAndFlush(oldBinding);
        UserMessage legacy = repository.saveAndFlush(new UserMessage(student, null, null,
            UserMessage.Kind.REVIEW_APPROVED, "来源未知", "待人工核实", "msg-legacy-event"));
        WechatStudentBinding newBinding = bind(student, "msg-new-wechat");
        asMiniapp(newBinding);
        assertEquals(List.of(), messages.mine());
        assertEquals(0, messages.unread());
        assertEquals(404, assertThrows(ApiException.class, () -> messages.read(oldMessageId)).status.value());
        assertEquals(404, assertThrows(ApiException.class, () -> messages.read(legacy.id)).status.value());
        messages.send(student, null, UserMessage.Kind.ORGANIZER_GRANTED,
            "新身份消息", "当前身份内容", "msg-new-event");
        assertEquals(1, messages.unread());
        assertEquals("新身份消息", messages.mine().get(0).title());
        as(student);
        assertEquals("新身份消息", messages.mine().get(0).title());
    }

    @Test
    void sameWechatKeepsItsMessageWhenStudentAccountChanges() {
        UserAccount previous = users.saveAndFlush(new UserAccount("原账号", "msg-source@test.local",
            "unused", UserAccount.Role.STUDENT));
        UserAccount corrected = users.saveAndFlush(new UserAccount("正确账号", "msg-target@test.local",
            "unused", UserAccount.Role.STUDENT));
        WechatStudentBinding first = bind(previous, "msg-moving-wechat");
        messages.send(previous, null, UserMessage.Kind.ORGANIZER_GRANTED,
            "本人原消息", "本人的历史通知", "msg-moving-event");
        first.unboundAt = Instant.now();
        bindings.saveAndFlush(first);
        WechatStudentBinding second = bindings.saveAndFlush(new WechatStudentBinding(first.identity, corrected));
        asMiniapp(second);
        assertEquals("本人原消息", messages.mine().get(0).title());
        assertEquals(1, messages.unread());
    }
}

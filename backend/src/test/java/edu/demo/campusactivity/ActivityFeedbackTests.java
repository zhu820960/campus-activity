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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:activity_feedback;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ActivityFeedbackTests {
    @Autowired UserRepository users;
    @Autowired ActivityRepository activityRepository;
    @Autowired ActivityService activities;
    @Autowired CheckinRepository checkins;
    @Autowired ActivityFeedbackRepository feedbackRepository;
    @Autowired ActivityFeedbackService feedback;
    @Autowired ActivityAuditRepository audits;
    @Autowired CurrentUser current;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(UserAccount account) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(account.id, null, List.of()));
    }

    @Test
    void onlyAttendeeAfterEndCanEvaluateOnceEditAndAdminCanHide() {
        UserAccount organizer = users.saveAndFlush(new UserAccount("组织者", "feedback-organizer@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        UserAccount otherOrganizer = users.saveAndFlush(new UserAccount("其他组织者", "feedback-other@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "feedback-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        UserAccount attendee = users.saveAndFlush(new UserAccount("到场学生", "feedback-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        UserAccount outsider = users.saveAndFlush(new UserAccount("未参加学生", "feedback-outsider@test.local",
            "unused", UserAccount.Role.STUDENT));
        Instant now = Instant.now();
        ActivityService.ActivityInput input = new ActivityService.ActivityInput("评价活动", "正文", "摘要",
            null, "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(60), now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 20);
        as(organizer);
        Long id = activities.create(input).id();
        activities.submit(id);
        as(admin);
        activities.review(id, true, "");
        as(attendee);
        assertEquals(403, assertThrows(ApiException.class,
            () -> feedback.submit(id, new ActivityFeedbackService.Input(5, "活动很好"))).status.value());

        Activity finished = activityRepository.findById(id).orElseThrow();
        finished.endsAt = Instant.now().minusSeconds(1);
        activityRepository.saveAndFlush(finished);
        checkins.saveAndFlush(new Checkin(finished, attendee, null, Checkin.Method.QR, null));
        as(outsider);
        assertEquals(403, assertThrows(ApiException.class,
            () -> feedback.submit(id, new ActivityFeedbackService.Input(5, "未参加"))).status.value());

        as(attendee);
        ActivityFeedbackView first = feedback.submit(id, new ActivityFeedbackService.Input(4, "组织清晰"));
        ActivityFeedbackView updated = feedback.submit(id, new ActivityFeedbackService.Input(5, "组织很好"));
        assertEquals(first.id(), updated.id());
        assertEquals(1, feedbackRepository.count());
        assertEquals(5, feedback.mine(id).rating());

        as(otherOrganizer);
        assertEquals(403, assertThrows(ApiException.class,
            () -> feedback.forOrganizer(id)).status.value());
        as(organizer);
        assertTrue(users.findById(organizer.id).orElseThrow().roles.contains(UserAccount.Role.ORGANIZER));
        assertEquals(organizer.id, current.require(UserAccount.Role.ORGANIZER).id);
        assertEquals(1, feedback.forOrganizer(id).size());
        assertEquals(5.0, feedback.summary(id).averageRating());
        as(admin);
        long auditsBefore = audits.count();
        assertEquals(400, assertThrows(ApiException.class,
            () -> feedback.hide(first.id(), " ")).status.value());
        ActivityFeedbackView hidden = feedback.hide(first.id(), "不当内容");
        assertNotNull(hidden.hiddenAt());
        assertEquals(auditsBefore + 1, audits.count());
        assertEquals(1, feedback.forAdmin(id).size());
        assertEquals(0, feedback.summary(id).count());
        as(attendee);
        assertEquals(409, assertThrows(ApiException.class,
            () -> feedback.submit(id, new ActivityFeedbackService.Input(4, "再修改"))).status.value());
    }
}

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

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:review_history;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ReviewHistoryTests {
    @Autowired UserRepository users;
    @Autowired ActivityService activities;
    @Autowired OrganizerReviewHistory history;
    @Autowired ActivityAuditRepository audits;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(UserAccount account) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(account.id, null, List.of()));
    }

    @Test
    void preservesEachReviewReasonAcrossResubmissionAndRestrictsOwner() {
        UserAccount organizer = users.saveAndFlush(new UserAccount("发起人", "review-owner@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        UserAccount other = users.saveAndFlush(new UserAccount("其他人", "review-other@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        UserAccount admin = users.saveAndFlush(new UserAccount("审核人", "review-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        Instant now = Instant.now();
        ActivityService.ActivityInput input = new ActivityService.ActivityInput("审核历史活动", "正文", "导语",
            null, "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(60), now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 20);
        as(organizer);
        Long id = activities.create(input).id();
        activities.submit(id);
        as(admin);
        activities.review(id, false, "第一次：地点需明确");
        as(organizer);
        activities.edit(id, input);
        activities.submit(id);
        as(admin);
        activities.review(id, false, "第二次：时间需确认");
        as(organizer);
        activities.edit(id, input);
        activities.submit(id);
        as(admin);
        activities.review(id, true, "信息已核实");
        assertEquals(null, activities.find(id).reviewNote);
        as(other);
        assertEquals(403, assertThrows(ApiException.class, () -> history.list(id)).status.value());
        as(organizer);
        List<ActivityAuditView> result = history.list(id);
        assertEquals(3, result.size());
        assertEquals("信息已核实", result.get(0).detail());
        assertEquals("第二次：时间需确认", result.get(1).detail());
        assertEquals("第一次：地点需明确", result.get(2).detail());
        assertEquals(3, audits.findByActivityIdOrderByIdDesc(id).stream()
            .filter(event -> event.action == ActivityAudit.Action.REVIEW_APPROVED
                || event.action == ActivityAudit.Action.REVIEW_REJECTED).count());
    }
}

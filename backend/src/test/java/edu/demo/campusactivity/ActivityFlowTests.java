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
    "spring.datasource.url=jdbc:h2:mem:activity_flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ActivityFlowTests {
    @Autowired UserRepository users;
    @Autowired ActivityService activities;
    @Autowired RegistrationService registrations;
    @Autowired CheckinService checkins;
    @Autowired ActivityAuditRepository audits;
    @Autowired AnnouncementController publicAnnouncements;
    @Autowired AdminAnnouncementController adminAnnouncements;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void organizerCanPublishAndStudentCanCheckIn() {
        UserAccount organizer = users.saveAndFlush(new UserAccount("组织者", "organizer@test.local", "unused",
            UserAccount.Role.ORGANIZER));
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "admin@test.local", "unused",
            UserAccount.Role.ADMIN));
        UserAccount student = users.saveAndFlush(new UserAccount("学生", "student@test.local", "unused",
            UserAccount.Role.STUDENT));
        Instant now = Instant.now();

        authenticate(organizer);
        ActivityView draft = activities.create(new ActivityService.ActivityInput("测试活动", "活动说明", "导语", null, "A101", 108.93, 34.34,
            now.plusSeconds(3600), now.plusSeconds(7200), now.minusSeconds(600), now.plusSeconds(1800),
            now.minusSeconds(60), now.plusSeconds(3600), 2));
        assertEquals(Activity.Status.PENDING, activities.submit(draft.id()).status());

        authenticate(admin);
        assertEquals(Activity.Status.PUBLISHED, activities.review(draft.id(), true, "").status());

        authenticate(student);
        assertEquals(Registration.Status.ACTIVE, registrations.register(draft.id()).status());
        assertEquals(1, registrations.mine().size());

        authenticate(organizer);
        String token = (String) checkins.issueToken(draft.id()).get("token");
        assertEquals(1, registrations.forActivity(draft.id()).size());

        authenticate(student);
        assertEquals(Checkin.Method.QR, checkins.scan(draft.id(), token).checkinMethod());
        assertThrows(ApiException.class, () -> registrations.register(draft.id()));

        authenticate(organizer);
        StatsView stats = checkins.stats(draft.id());
        assertEquals(1, stats.registeredCount());
        assertEquals(1, stats.checkedCount());

        authenticate(admin);
        assertEquals(1, checkins.adminOverview().stream()
            .filter(row -> row.activity().id().equals(draft.id()))
            .findFirst().orElseThrow().stats().checkedCount());
        assertEquals(1, registrations.forAdmin(draft.id()).size());
        assertThrows(ApiException.class, () -> checkins.issueToken(draft.id()));
    }

    @Test
    void articleStaysPrivateUntilReviewAndNoticesRequireAdmin() {
        UserAccount organizer = users.saveAndFlush(new UserAccount("组织者二", "organizer2@test.local", "unused",
            UserAccount.Role.ORGANIZER));
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员二", "admin2@test.local", "unused",
            UserAccount.Role.ADMIN));
        UserAccount student = users.saveAndFlush(new UserAccount("学生二", "student2@test.local", "unused",
            UserAccount.Role.STUDENT));
        Instant now = Instant.now();

        authenticate(organizer);
        ActivityView noPoint = activities.create(new ActivityService.ActivityInput(
            "无坐标活动", "说明", "导语", null, "报告厅", null, null,
            now.plusSeconds(3600), now.plusSeconds(7200), now.minusSeconds(600), now.plusSeconds(1800),
            now.minusSeconds(60), now.plusSeconds(3600), 20));
        assertEquals(409, assertThrows(ApiException.class, () -> activities.submit(noPoint.id())).status.value());
        ActivityView draft = activities.create(new ActivityService.ActivityInput("推文活动", "推文正文", "活动导语",
            "/images/campus-gathering.png", "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(600), now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 20));
        assertEquals("活动导语", draft.articleSummary());
        assertEquals(false, activities.published().stream().anyMatch(a -> a.id().equals(draft.id())));
        activities.submit(draft.id());

        authenticate(student);
        assertThrows(ApiException.class, () -> activities.visible(draft.id()));
        assertThrows(ApiException.class, () -> adminAnnouncements.publish(
            new AdminAnnouncementController.Input("通知", "正文")));

        authenticate(admin);
        ActivityView published = activities.review(draft.id(), true, "");
        assertEquals("活动导语", published.articleSummary());
        assertEquals(true, published.publishedAt() != null);
        AnnouncementView notice = adminAnnouncements.publish(new AdminAnnouncementController.Input("通知", "正文"));

        authenticate(student);
        assertEquals(true, activities.published().stream().anyMatch(a -> a.id().equals(draft.id())));
        assertThrows(ApiException.class, () -> publicAnnouncements.published());
        assertThrows(ApiException.class, () -> adminAnnouncements.withdraw(notice.id()));

        authenticate(admin);
        assertEquals(true, publicAnnouncements.published().stream().anyMatch(a -> a.id().equals(notice.id())));
        adminAnnouncements.withdraw(notice.id());
        assertEquals(false, publicAnnouncements.published().stream().anyMatch(a -> a.id().equals(notice.id())));
        adminAnnouncements.edit(notice.id(), new AdminAnnouncementController.Input("更新通知", "更新正文"));
        assertEquals(false, publicAnnouncements.published().stream().anyMatch(a -> a.id().equals(notice.id())));
        assertEquals(true, adminAnnouncements.republish(notice.id()).active());
        assertEquals(true, publicAnnouncements.published().stream().anyMatch(a -> a.id().equals(notice.id())));
        assertEquals(409, assertThrows(ApiException.class,
            () -> adminAnnouncements.republish(notice.id())).status.value());
    }

    @Test
    void adminPublishesOwnActivityWithoutReviewAndCanDisplayAnyPublishedQr() {
        UserAccount admin = users.saveAndFlush(new UserAccount("发布管理员", "publishing-admin@test.local", "unused",
            UserAccount.Role.ADMIN));
        UserAccount organizer = users.saveAndFlush(new UserAccount("活动组织者", "qr-organizer@test.local", "unused",
            UserAccount.Role.ORGANIZER));
        UserAccount student = users.saveAndFlush(new UserAccount("扫码学生", "qr-student@test.local", "unused",
            UserAccount.Role.STUDENT));
        Instant now = Instant.now();
        ActivityService.ActivityInput input = new ActivityService.ActivityInput("现场活动", "活动正文", "活动导语",
            null, "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(600), now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 20);

        authenticate(admin);
        ActivityView own = activities.createForAdmin(input);
        assertEquals(Activity.Status.DRAFT, own.status());
        assertEquals(Activity.Status.PUBLISHED, activities.publishOwnForAdmin(own.id()).status());
        assertEquals(List.of(ActivityAudit.Action.DIRECT_PUBLISHED, ActivityAudit.Action.CREATED),
            audits.findByActivityIdOrderByIdDesc(own.id()).stream().map(event -> event.action).toList());
        assertThrows(ApiException.class, () -> activities.publishOwnForAdmin(own.id()));

        authenticate(organizer);
        assertThrows(ApiException.class, () -> activities.publishOwnForAdmin(own.id()));
        assertThrows(ApiException.class, () -> checkins.issueAdminToken(own.id()));
        ActivityView submitted = activities.create(input);
        activities.submit(submitted.id());

        authenticate(admin);
        assertThrows(ApiException.class, () -> activities.publishOwnForAdmin(submitted.id()));
        activities.review(submitted.id(), true, "");
        String token = (String) checkins.issueAdminToken(submitted.id()).get("token");
        assertEquals(ActivityAudit.Action.QR_ISSUED,
            audits.findByActivityIdOrderByIdDesc(submitted.id()).get(0).action);

        authenticate(student);
        assertEquals(Activity.Status.PUBLISHED, activities.visible(own.id()).status());
        registrations.register(submitted.id());
        assertEquals(Checkin.Method.QR, checkins.scan(submitted.id(), token).checkinMethod());
    }

    private void authenticate(UserAccount user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
    }
}

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

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:activity_deadlines;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ActivityDeadlineTests {
    @Autowired UserRepository users;
    @Autowired ActivityRepository repository;
    @Autowired ActivityService activities;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void expiredPendingCannotBeApprovedButCanBeRejectedRevisedAndResubmitted() {
        UserAccount organizer = user("deadline-organizer", UserAccount.Role.ORGANIZER);
        UserAccount admin = user("deadline-admin", UserAccount.Role.ADMIN);
        UserAccount student = user("deadline-student", UserAccount.Role.STUDENT);

        authenticate(organizer);
        ActivityView pending = activities.create(input("即将开始"));
        assertEquals(Activity.Status.PENDING, activities.submit(pending.id()).status());

        // Simulate time passing while the activity is waiting for review.
        moveToPast(pending.id(), false);
        authenticate(admin);
        ApiException started = assertThrows(ApiException.class, () -> activities.review(pending.id(), true, ""));
        assertEquals("活动已开始，无法通过审核，请驳回后修改活动时间", started.getMessage());
        assertEquals(Activity.Status.PENDING, repository.findById(pending.id()).orElseThrow().status);
        authenticate(student);
        assertFalse(activities.published().stream().anyMatch(a -> a.id().equals(pending.id())));

        authenticate(admin);
        assertEquals(Activity.Status.REJECTED, activities.review(pending.id(), false, "请调整时间").status());
        authenticate(organizer);
        activities.edit(pending.id(), input("调整后的活动"));
        assertEquals(Activity.Status.PENDING, activities.submit(pending.id()).status());
        authenticate(admin);
        assertEquals(Activity.Status.PUBLISHED, activities.review(pending.id(), true, "").status());

        authenticate(organizer);
        ActivityView ended = activities.create(input("已结束的待审活动"));
        activities.submit(ended.id());
        moveToPast(ended.id(), true);
        authenticate(admin);
        assertThrows(ApiException.class, () -> activities.review(ended.id(), true, ""));
        assertEquals(Activity.Status.PENDING, repository.findById(ended.id()).orElseThrow().status);
    }

    @Test
    void staleDraftCannotBeSubmittedAndAdminCanStillPublishOwnFutureActivity() {
        UserAccount organizer = user("draft-organizer", UserAccount.Role.ORGANIZER);
        UserAccount admin = user("draft-admin", UserAccount.Role.ADMIN);
        authenticate(organizer);
        ActivityView draft = activities.create(input("旧草稿"));
        moveToPast(draft.id(), false);
        assertThrows(ApiException.class, () -> activities.submit(draft.id()));
        assertEquals(Activity.Status.DRAFT, repository.findById(draft.id()).orElseThrow().status);

        authenticate(admin);
        ActivityView own = activities.createForAdmin(input("管理员新活动"));
        assertEquals(Activity.Status.PUBLISHED, activities.publishOwnForAdmin(own.id()).status());
    }

    @Test
    void locationCanBeCompletedAfterSavingDraftButIsRequiredForPublication() {
        UserAccount organizer = user("map-organizer", UserAccount.Role.ORGANIZER);
        UserAccount admin = user("map-admin", UserAccount.Role.ADMIN);
        ActivityService.ActivityInput full = input("待选点活动");
        ActivityService.ActivityInput noPoint = new ActivityService.ActivityInput(full.title(),
            full.description(), full.articleSummary(), full.coverImageUrl(), full.location(), null, null,
            full.startsAt(), full.endsAt(), full.registrationOpensAt(), full.registrationClosesAt(),
            full.checkinOpensAt(), full.checkinClosesAt(), full.capacity());

        authenticate(organizer);
        ActivityView draft = activities.create(noPoint);
        assertEquals(Activity.Status.DRAFT, draft.status());
        assertEquals(409, assertThrows(ApiException.class,
            () -> activities.submit(draft.id())).status.value());
        activities.edit(draft.id(), full);
        assertEquals(Activity.Status.PENDING, activities.submit(draft.id()).status());

        authenticate(admin);
        ActivityView ownDraft = activities.createForAdmin(noPoint);
        assertEquals(409, assertThrows(ApiException.class,
            () -> activities.publishOwnForAdmin(ownDraft.id())).status.value());
        activities.editOwnForAdmin(ownDraft.id(), full);
        assertEquals(Activity.Status.PUBLISHED, activities.publishOwnForAdmin(ownDraft.id()).status());
    }

    private ActivityService.ActivityInput input(String title) {
        Instant now = Instant.now();
        return new ActivityService.ActivityInput(title, "正文", "导语", null, "报告厅", 108.93, 34.34,
            now.plusSeconds(3600), now.plusSeconds(7200), now.minusSeconds(60), now.plusSeconds(1800),
            now.minusSeconds(30), now.plusSeconds(3600), 20);
    }

    private void moveToPast(Long id, boolean ended) {
        Activity activity = repository.findById(id).orElseThrow();
        activity.startsAt = Instant.now().minusSeconds(ended ? 7200 : 1);
        if (ended) activity.endsAt = Instant.now().minusSeconds(3600);
        repository.saveAndFlush(activity);
    }

    private UserAccount user(String name, UserAccount.Role role) {
        return users.saveAndFlush(new UserAccount(name, name + "@test.local", "unused", role));
    }

    private void authenticate(UserAccount user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
    }
}

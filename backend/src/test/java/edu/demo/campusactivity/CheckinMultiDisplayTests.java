package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    "spring.datasource.url=jdbc:h2:mem:checkin_displays;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CheckinMultiDisplayTests {
    @Autowired UserRepository users;
    @Autowired ActivityRepository repository;
    @Autowired ActivityService activities;
    @Autowired RegistrationService registrations;
    @Autowired CheckinService checkins;
    @Autowired CheckinTokenSigner signer;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void organizerAndAdminCodesStayValidIndependently() {
        UserAccount organizer = user("display-organizer", UserAccount.Role.ORGANIZER);
        UserAccount admin = user("display-admin", UserAccount.Role.ADMIN);
        UserAccount first = user("display-first", UserAccount.Role.STUDENT);
        UserAccount second = user("display-second", UserAccount.Role.STUDENT);
        UserAccount cancelled = user("display-cancelled", UserAccount.Role.STUDENT);
        UserAccount outsider = user("display-outsider", UserAccount.Role.STUDENT);
        Long activityId = publish("活动 A", organizer, admin);
        Long otherId = publish("活动 B", organizer, admin);

        authenticate(first); registrations.register(activityId); registrations.register(otherId);
        authenticate(second); registrations.register(activityId);
        authenticate(cancelled); registrations.register(activityId); registrations.cancel(activityId);

        authenticate(organizer);
        String firstCode = (String) checkins.issueToken(activityId).get("token");
        authenticate(admin);
        String secondCode = (String) checkins.issueAdminToken(activityId).get("token");
        authenticate(organizer);
        String refreshedCode = (String) checkins.issueToken(activityId).get("token");

        authenticate(outsider);
        assertThrows(ApiException.class, () -> checkins.scan(activityId, secondCode));
        authenticate(cancelled);
        assertThrows(ApiException.class, () -> checkins.scan(activityId, secondCode));
        authenticate(first);
        assertThrows(ApiException.class, () -> checkins.scan(otherId, firstCode));
        assertEquals(Checkin.Method.QR, checkins.scan(activityId, firstCode).checkinMethod());
        assertThrows(ApiException.class, () -> checkins.scan(activityId, secondCode));
        authenticate(second);
        assertEquals(Checkin.Method.QR, checkins.scan(activityId, refreshedCode).checkinMethod());
        authenticate(admin);
        assertEquals(2, checkins.adminStats(activityId).checkedCount());
    }

    @Test
    void nearSimultaneousIssuanceDoesNotInvalidateEitherCode() throws Exception {
        UserAccount organizer = user("concurrent-organizer", UserAccount.Role.ORGANIZER);
        UserAccount admin = user("concurrent-admin", UserAccount.Role.ADMIN);
        UserAccount first = user("concurrent-first", UserAccount.Role.STUDENT);
        UserAccount second = user("concurrent-second", UserAccount.Role.STUDENT);
        Long activityId = publish("并发签发", organizer, admin);
        authenticate(first); registrations.register(activityId);
        authenticate(second); registrations.register(activityId);

        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> organizerIssue = () -> {
                start.await();
                authenticate(organizer);
                try { return (String) checkins.issueToken(activityId).get("token"); }
                finally { SecurityContextHolder.clearContext(); }
            };
            Callable<String> adminIssue = () -> {
                start.await();
                authenticate(admin);
                try { return (String) checkins.issueAdminToken(activityId).get("token"); }
                finally { SecurityContextHolder.clearContext(); }
            };
            var firstFuture = pool.submit(organizerIssue);
            var secondFuture = pool.submit(adminIssue);
            start.countDown();
            String firstCode = firstFuture.get(10, TimeUnit.SECONDS);
            String secondCode = secondFuture.get(10, TimeUnit.SECONDS);
            authenticate(first);
            assertEquals(Checkin.Method.QR, checkins.scan(activityId, firstCode).checkinMethod());
            authenticate(second);
            assertEquals(Checkin.Method.QR, checkins.scan(activityId, secondCode).checkinMethod());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tokenExpiryActivityBindingAndWindowAreEnforced() {
        Instant now = Instant.now();
        CheckinTokenSigner.Issued old = signer.issue(101L, now.minusSeconds(91));
        assertFalse(signer.valid(101L, old.token(), now));
        CheckinTokenSigner.Issued current = signer.issue(101L, now);
        assertTrue(signer.valid(101L, current.token(), now));
        assertFalse(signer.valid(101L, current.token(), current.expiresAt()));
        assertFalse(signer.valid(102L, current.token(), now));
        assertFalse(signer.valid(101L, current.token() + "x", now));
        signer.issue(101L, now.plusSeconds(30));
        assertFalse(signer.valid(101L, current.token(), current.expiresAt()));

        UserAccount organizer = user("window-organizer", UserAccount.Role.ORGANIZER);
        UserAccount admin = user("window-admin", UserAccount.Role.ADMIN);
        UserAccount student = user("window-student", UserAccount.Role.STUDENT);
        Long activityId = publish("时间窗口", organizer, admin);
        authenticate(student);
        registrations.register(activityId);
        String expiredCode = signer.issue(activityId, now.minusSeconds(91)).token();
        assertThrows(ApiException.class, () -> checkins.scan(activityId, expiredCode));
        authenticate(organizer);
        String currentCode = (String) checkins.issueToken(activityId).get("token");
        Activity activity = repository.findById(activityId).orElseThrow();
        activity.checkinOpensAt = now.plusSeconds(60);
        repository.saveAndFlush(activity);
        authenticate(organizer);
        assertThrows(ApiException.class, () -> checkins.issueToken(activityId));
        authenticate(student);
        assertThrows(ApiException.class, () -> checkins.scan(activityId, currentCode));
        activity = repository.findById(activityId).orElseThrow();
        activity.checkinOpensAt = now.minusSeconds(60);
        activity.checkinClosesAt = now.minusSeconds(1);
        repository.saveAndFlush(activity);
        authenticate(admin);
        assertThrows(ApiException.class, () -> checkins.issueAdminToken(activityId));
        authenticate(student);
        assertThrows(ApiException.class, () -> checkins.scan(activityId, currentCode));
    }

    private Long publish(String title, UserAccount organizer, UserAccount admin) {
        Instant now = Instant.now();
        authenticate(organizer);
        ActivityView draft = activities.create(new ActivityService.ActivityInput(title, "正文", "导语", null,
            "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(60), now.plusSeconds(1800), now.minusSeconds(30), now.plusSeconds(3600), 20));
        activities.submit(draft.id());
        authenticate(admin);
        activities.review(draft.id(), true, "");
        return draft.id();
    }

    private UserAccount user(String name, UserAccount.Role role) {
        return users.saveAndFlush(new UserAccount(name, name + "@test.local", "unused", role));
    }

    private void authenticate(UserAccount user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
    }
}

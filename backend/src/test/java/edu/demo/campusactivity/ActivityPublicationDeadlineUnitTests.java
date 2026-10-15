package edu.demo.campusactivity;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActivityPublicationDeadlineUnitTests {
    @Test
    void directPublicationOpensRegistrationImmediately() {
        var activities = org.mockito.Mockito.mock(ActivityRepository.class);
        var current = org.mockito.Mockito.mock(CurrentUser.class);
        var admin = new UserAccount("管理员", null, null, UserAccount.Role.ADMIN); admin.id = 1L;
        var activity = new Activity(); activity.id = 10L; activity.title = "活动"; activity.organizer = admin;
        activity.startsAt = Instant.now().plusSeconds(7200);
        activity.endsAt = activity.startsAt.plusSeconds(3600);
        activity.registrationClosesAt = activity.startsAt.minusSeconds(1800);
        activity.registrationOpensAt = Instant.now().plusSeconds(3600);
        activity.longitude = 108.9; activity.latitude = 34.2;
        org.mockito.Mockito.when(current.require(UserAccount.Role.ADMIN)).thenReturn(admin);
        org.mockito.Mockito.when(activities.findById(10L)).thenReturn(java.util.Optional.of(activity));
        var service = new ActivityService(activities, org.mockito.Mockito.mock(ActivityAuditRepository.class),
            current, org.mockito.Mockito.mock(UserMessageService.class));
        service.publishOwnForAdmin(10L);
        assertEquals(activity.publishedAt, activity.registrationOpensAt);
        assertEquals(Activity.Status.PUBLISHED, activity.status);
    }
    @Test
    void publicationRequiresAnOpenRegistrationWindow() {
        Instant now = Instant.parse("2026-10-13T02:00:00Z");
        Activity activity = new Activity();
        activity.registrationClosesAt = now;

        ApiException error = assertThrows(ApiException.class,
            () -> ActivityService.requireRegistrationStillOpen(activity, now));
        assertEquals(HttpStatus.CONFLICT, error.status);
        assertEquals("报名截止时间已过，请先调整活动时间", error.getMessage());

        activity.registrationClosesAt = now.plusSeconds(1);
        assertDoesNotThrow(() -> ActivityService.requireRegistrationStillOpen(activity, now));
    }
}

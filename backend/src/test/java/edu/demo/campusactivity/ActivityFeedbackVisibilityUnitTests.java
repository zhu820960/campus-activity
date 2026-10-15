package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ActivityFeedbackVisibilityUnitTests {
    private final ActivityRepository activities = mock(ActivityRepository.class);
    private final ActivityFeedbackRepository repository = mock(ActivityFeedbackRepository.class);
    private final CurrentUser current = mock(CurrentUser.class);
    private final ActivityFeedbackService service = new ActivityFeedbackService(activities, repository,
        mock(CheckinRepository.class), mock(ActivityAuditRepository.class), current);

    @Test
    void organizerSeesOnlyVisibleFeedbackOnOwnedActivity() {
        Activity activity = new Activity();
        activity.id = 7L;
        when(activities.findById(7L)).thenReturn(Optional.of(activity));
        UserAccount organizer = new UserAccount("组织者", null, null, UserAccount.Role.ORGANIZER);
        when(current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER)).thenReturn(organizer);
        when(current.require(UserAccount.Role.ORGANIZER)).thenReturn(organizer);

        ActivityFeedback visible = new ActivityFeedback(activity,
            new UserAccount("学生", null, null, UserAccount.Role.STUDENT), 5, "很好");
        ActivityFeedback hidden = new ActivityFeedback(activity,
            new UserAccount("学生二", null, null, UserAccount.Role.STUDENT), 1, "已隐藏");
        hidden.hiddenAt = Instant.now();
        when(repository.findByActivityIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(visible, hidden));

        FeedbackSummary summary = service.summary(7L);
        assertEquals(1, summary.count());
        assertEquals(5.0, summary.averageRating());
        assertEquals(1, service.forOrganizer(7L).size());
        verify(current, org.mockito.Mockito.times(2)).owns(activity);
    }

    @Test
    void otherOrganizersActivityCannotBeRead() {
        Activity activity = new Activity();
        when(activities.findById(7L)).thenReturn(Optional.of(activity));
        when(current.require(UserAccount.Role.ADMIN, UserAccount.Role.ORGANIZER))
            .thenReturn(new UserAccount("组织者", null, null, UserAccount.Role.ORGANIZER));
        when(current.require(UserAccount.Role.ORGANIZER))
            .thenReturn(new UserAccount("组织者", null, null, UserAccount.Role.ORGANIZER));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "只能管理自己的活动"))
            .when(current).owns(activity);

        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> service.summary(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> service.forOrganizer(7L)).status);
        verifyNoInteractions(repository);
    }
}

package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MiniappOrganizerControllerTests {
    private final ActivityService activities = mock(ActivityService.class);
    private final RegistrationService registrations = mock(RegistrationService.class);
    private final CheckinService checkins = mock(CheckinService.class);
    private final ActivityFeedbackService feedback = mock(ActivityFeedbackService.class);
    private final OrganizerReviewHistory reviews = mock(OrganizerReviewHistory.class);
    private final CurrentUser current = mock(CurrentUser.class);
    private final MiniappOrganizerController controller =
        new MiniappOrganizerController(activities, registrations, checkins, feedback, reviews, current);

    @BeforeEach
    void organizerStudent() {
        UserAccount user = new UserAccount("组织者", null, null, UserAccount.Role.STUDENT);
        user.roles.add(UserAccount.Role.ORGANIZER);
        when(current.require(UserAccount.Role.STUDENT)).thenReturn(user);
    }

    @Test
    void organizerCanListAndSubmitOnlyThroughOrganizerService() {
        when(activities.mine()).thenReturn(List.of());
        assertEquals(List.of(), controller.mine());
        controller.submit(7L);
        verify(activities).mine();
        verify(activities).submit(7L);
    }

    @Test
    void organizerOperationsDelegateToOwnershipCheckingServices() {
        when(registrations.forActivity(7L)).thenReturn(List.of());
        when(checkins.issueToken(7L)).thenReturn(Map.of("token", "test"));

        assertEquals(List.of(), controller.registrations(7L));
        controller.stats(7L);
        assertEquals("test", controller.token(7L).get("token"));
        controller.manual(7L, new MiniappOrganizerController.ManualRequest(5L, "现场核对"));

        verify(registrations).forActivity(7L);
        verify(checkins).stats(7L);
        verify(checkins).issueToken(7L);
        verify(checkins).manual(7L, 5L, "现场核对");
    }

    @Test
    void organizerFeedbackUsesOwnershipCheckingService() {
        when(feedback.forOrganizer(7L)).thenReturn(List.of());
        when(feedback.summary(7L)).thenReturn(new FeedbackSummary(2, 4.5));
        assertEquals(List.of(), controller.feedback(7L));
        assertEquals(4.5, controller.feedbackSummary(7L).averageRating());
        verify(feedback).forOrganizer(7L);
        verify(feedback).summary(7L);
    }

    @Test
    void organizerReviewsUseOwnershipCheckingHistory() {
        when(reviews.list(7L)).thenReturn(List.of());
        assertEquals(List.of(), controller.reviews(7L));
        verify(reviews).list(7L);
    }

    @Test
    void ordinaryStudentCannotReadOrganizerActivities() {
        when(current.require(UserAccount.Role.STUDENT))
            .thenReturn(new UserAccount("学生", null, null, UserAccount.Role.STUDENT));
        ApiException error = assertThrows(ApiException.class, controller::mine);
        assertEquals(HttpStatus.FORBIDDEN, error.status);
        verifyNoInteractions(activities, registrations, checkins, feedback, reviews);
    }

    @Test
    void ordinaryStudentCannotUseRosterOrCheckinOperations() {
        when(current.require(UserAccount.Role.STUDENT))
            .thenReturn(new UserAccount("学生", null, null, UserAccount.Role.STUDENT));

        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.registrations(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.stats(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.token(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.manual(7L,
                new MiniappOrganizerController.ManualRequest(5L, "现场核对"))).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.feedback(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.feedbackSummary(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.reviews(7L)).status);
        verifyNoInteractions(activities, registrations, checkins, feedback, reviews);
    }

    @Test
    void administratorHasNoMobileOrganizerAccess() {
        UserAccount admin = new UserAccount("管理员", null, null, UserAccount.Role.STUDENT);
        admin.roles.add(UserAccount.Role.ORGANIZER);
        admin.roles.add(UserAccount.Role.ADMIN);
        when(current.require(UserAccount.Role.STUDENT)).thenReturn(admin);
        ApiException error = assertThrows(ApiException.class, () -> controller.detail(7L));
        assertEquals(HttpStatus.FORBIDDEN, error.status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.registrations(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.token(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.feedback(7L)).status);
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> controller.reviews(7L)).status);
        verifyNoInteractions(activities, registrations, checkins, feedback, reviews);
    }
}

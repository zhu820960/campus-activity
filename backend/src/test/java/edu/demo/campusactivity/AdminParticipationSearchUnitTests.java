package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

class AdminParticipationSearchUnitTests {
    private final RegistrationRepository registrations = mock(RegistrationRepository.class);
    private final CheckinRepository checkins = mock(CheckinRepository.class);
    private final CurrentUser current = mock(CurrentUser.class);
    private final AdminParticipationSearch search = new AdminParticipationSearch(registrations, checkins, current);

    @Test
    void rejectsNonAdminBeforeReadingRecords() {
        when(current.require(UserAccount.Role.ADMIN))
            .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "没有操作权限"));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ApiException.class, () -> search.search(0, 20, null, null, null, null, null)).status);
        verifyNoInteractions(registrations, checkins);
    }

    @Test
    void rejectsInvalidFiltersBeforeQuerying() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ApiException.class, () -> search.search(-1, 20, null, null, null, null, null)).status);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ApiException.class, () -> search.search(0, 101, null, null, null, null, null)).status);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ApiException.class, () -> search.search(0, 20, "a".repeat(81), null, null, null, null)).status);
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ApiException.class, () -> search.search(0, 20, null, null, null,
                LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-13"))).status);
        verifyNoInteractions(registrations, checkins);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsExistingRegistrationAndCheckinWithoutChangingThem() {
        Activity activity = new Activity();
        activity.id = 7L;
        activity.title = "测试活动";
        UserAccount student = UserAccount.selfRegisteredStudent("学生", "00000000000005");
        student.id = 5L;
        Registration registration = new Registration(activity, student);
        registration.id = 9L;
        Checkin checkin = new Checkin(activity, student, null, Checkin.Method.QR, null);
        checkin.checkedAt = Instant.parse("2026-10-13T02:00:00Z");
        when(registrations.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(registration)));
        when(checkins.findForRegistrations(List.of(9L))).thenReturn(List.of(checkin));

        AuditPage<RegistrationView> result = search.search(0, 20, "学生", Registration.Status.ACTIVE,
            AdminParticipationSearch.Attendance.CHECKED, null, null);

        assertEquals(1, result.total());
        assertEquals("00000000000005", result.items().get(0).studentNumber());
        assertEquals(checkin.checkedAt, result.items().get(0).checkedAt());
        verify(current).require(UserAccount.Role.ADMIN);
        verify(checkins).findForRegistrations(List.of(9L));
        verifyNoMoreInteractions(checkins);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyPageDoesNotQueryCheckins() {
        when(registrations.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        AuditPage<RegistrationView> result = search.search(0, 20, null, null, null, null, null);

        assertEquals(0, result.total());
        verifyNoInteractions(checkins);
    }
}

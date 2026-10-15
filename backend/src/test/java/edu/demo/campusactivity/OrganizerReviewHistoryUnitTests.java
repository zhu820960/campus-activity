package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class OrganizerReviewHistoryUnitTests {
    private final CurrentUser current = mock(CurrentUser.class);
    private final ActivityService activities = mock(ActivityService.class);
    private final ActivityAuditRepository audits = mock(ActivityAuditRepository.class);
    private final OrganizerReviewHistory history = new OrganizerReviewHistory(current, activities, audits);

    @Test
    void rejectsAnotherOrganizersActivityBeforeReadingAudit() {
        when(activities.owned(7L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "只能管理自己的活动"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> history.list(7L)).status);
        verifyNoInteractions(audits);
    }

    @Test
    void returnsOnlyReviewDecisions() {
        Activity activity = new Activity();
        activity.id = 7L;
        UserAccount admin = new UserAccount("管理员", null, null, UserAccount.Role.ADMIN);
        ActivityAudit created = new ActivityAudit(activity, admin, ActivityAudit.Action.CREATED);
        ActivityAudit rejected = new ActivityAudit(activity, admin, ActivityAudit.Action.REVIEW_REJECTED, "请修改时间");
        ActivityAudit approved = new ActivityAudit(activity, admin, ActivityAudit.Action.REVIEW_APPROVED);
        when(audits.findByActivityIdOrderByIdDesc(7L)).thenReturn(List.of(created, rejected, approved));

        List<ActivityAuditView> rows = history.list(7L);

        assertEquals(2, rows.size());
        assertEquals(ActivityAudit.Action.REVIEW_REJECTED, rows.get(0).action());
        assertEquals("请修改时间", rows.get(0).detail());
        assertEquals(ActivityAudit.Action.REVIEW_APPROVED, rows.get(1).action());
    }
}

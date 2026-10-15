package edu.demo.campusactivity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    "spring.datasource.url=jdbc:h2:mem:admin_audit_search;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdminAuditSearchTests {
    @Autowired AdminAuditSearch search;
    @Autowired UserRepository users;
    @Autowired ActivityRepository activities;
    @Autowired ActivityAuditRepository activityAudits;
    @Autowired UserSecurityAuditRepository userAudits;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(UserAccount account) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(account.id, null, List.of()));
    }

    @Test
    void filtersAndPaginatesActualAuditRowsWithAdminOnlyAccess() {
        UserAccount admin = users.saveAndFlush(new UserAccount("审计管理员", "audit-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        UserAccount other = users.saveAndFlush(new UserAccount("其他人", "audit-other@test.local",
            "unused", UserAccount.Role.STUDENT));
        Activity activity = new Activity();
        activity.organizer = admin;
        activity.title = "审计测试活动";
        activity.description = "测试";
        activity.location = "测试地点";
        activity.startsAt = Instant.now();
        activity.endsAt = Instant.now().plusSeconds(3600);
        activity.registrationOpensAt = Instant.now();
        activity.registrationClosesAt = Instant.now().plusSeconds(3600);
        activity.checkinOpensAt = Instant.now();
        activity.checkinClosesAt = Instant.now().plusSeconds(3600);
        activity.capacity = 10;
        activities.saveAndFlush(activity);
        activityAudits.saveAndFlush(new ActivityAudit(activity, admin, ActivityAudit.Action.CREATED));
        activityAudits.saveAndFlush(new ActivityAudit(activity, admin, ActivityAudit.Action.QR_ISSUED));
        userAudits.saveAndFlush(new UserSecurityAudit(admin, other,
            UserSecurityAudit.Action.ORGANIZER_GRANTED));

        as(other);
        assertEquals(403, assertThrows(ApiException.class,
            () -> search.activity(0, 20, null, null, null, null, null)).status.value());
        as(admin);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        AuditPage<AdminAuditOverview> page = search.activity(0, 1, activity.id,
            "审计管", null, today, today);
        assertEquals(2, page.total());
        assertEquals(1, page.items().size());
        assertEquals(ActivityAudit.Action.QR_ISSUED, page.items().get(0).action());
        assertEquals(1, search.activity(0, 20, null, null,
            ActivityAudit.Action.CREATED, null, null).total());
        assertEquals(0, search.activity(0, 20, null, "不存在", null, null, null).total());
        assertEquals(1, search.user(0, 20, "审计管理员",
            UserSecurityAudit.Action.ORGANIZER_GRANTED, today, today).total());
        assertEquals(400, assertThrows(ApiException.class,
            () -> search.user(0, 101, null, null, null, null)).status.value());
    }
}

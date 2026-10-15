package edu.demo.campusactivity;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "RUN_USER_DELETION_MYSQL_CHECK", matches = "true")
@ActiveProfiles("mysql")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = { "app.bootstrap-admin.email=", "app.bootstrap-admin.password=" })
@Transactional
class AdminUserDeletionMysqlTests {
    @Autowired AdminUserDeletionService service;
    @Autowired UserRepository users;
    @Autowired ActivityRepository activities;
    @Autowired RegistrationRepository registrations;
    @Autowired CheckinRepository checkins;
    @Autowired WechatIdentityRepository identities;
    @Autowired WechatStudentBindingRepository bindings;
    @Autowired BindingAppealRepository appeals;
    @Autowired ParticipationAttributionReviewRepository attribution;
    @Autowired ActivityChangeRequestRepository changes;
    @Autowired UserSecurityAuditRepository audits;
    @Autowired MiniappAuthService auth;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUser current;
    @Value("${app.wechat.app-id}") String appId;
    private long initialUsers;
    @BeforeTransaction void baseline() {
        assertEquals("campus_activity_miniapp_dev", jdbc.queryForObject("select database()", String.class));
        initialUsers = jdbc.queryForObject("select count(*) from user_accounts", Long.class);
    }
    @AfterTransaction void rolledBack() {
        assertEquals(initialUsers, jdbc.queryForObject("select count(*) from user_accounts", Long.class));
    }
    @Test
    void deletesOnlySyntheticAccountAndRollsBackAllFixtureData() {
        UserAccount admin = users.saveAndFlush(new UserAccount("[ROLLBACK] 删除测试管理员", null, null, UserAccount.Role.ADMIN));
        UserAccount target = new UserAccount("[ROLLBACK] 删除目标", null, "fixture-only", UserAccount.Role.STUDENT);
        target.roles.add(UserAccount.Role.ORGANIZER); target = users.saveAndFlush(target);
        final Long deletedId = target.id;
        when(current.require(UserAccount.Role.ADMIN)).thenReturn(admin);
        Activity future = activity(target, true);
        Activity past = activity(target, false);
        Registration waiting = registrations.saveAndFlush(new Registration(future, target));
        Registration attended = registrations.saveAndFlush(new Registration(past, target));
        Checkin checkin = checkins.saveAndFlush(new Checkin(past, target, target, Checkin.Method.MANUAL, "回滚测试"));
        WechatIdentity identity = identities.saveAndFlush(new WechatIdentity(appId, "rollback-delete-" + UUID.randomUUID()));
        WechatStudentBinding binding = bindings.saveAndFlush(new WechatStudentBinding(identity, target));
        waiting.bindingId = binding.id; waiting.identityId = identity.id;
        attended.bindingId = binding.id; attended.identityId = identity.id;
        checkin.bindingId = binding.id; checkin.identityId = identity.id;
        registrations.flush(); checkins.flush();
        String token = auth.login(identity.openId).token();
        BindingAppeal appeal = appeals.saveAndFlush(new BindingAppeal(identity, binding, BindingAppeal.Type.WRONG_STUDENT_NUMBER,
            "99999999999999", "回滚测试", "fixture-no-file", Instant.now()));
        attribution.saveAndFlush(new ParticipationAttributionReview(appeal, attended, checkin, target, target,
            ParticipationAttributionReview.Decision.KEEP, "回滚测试", target, Instant.now()));
        changes.saveAndFlush(new ActivityChangeRequest(future, target, "回滚测试", "{}"));
        audits.saveAndFlush(new UserSecurityAudit(target, target, UserSecurityAudit.Action.ORGANIZER_GRANTED));
        var impact = service.preview(deletedId);
        assertEquals(2, impact.activities());
        assertEquals(2, impact.registrations());
        service.delete(deletedId, deletedId);
        assertTrue(users.findById(deletedId).isEmpty());
        assertThrows(ApiException.class, () -> auth.authenticateWithAttribution(token));
        assertEquals(0L, jdbc.queryForObject("select count(*) from wechat_student_bindings where student_id = ?", Long.class, deletedId));
        assertEquals(admin.id, jdbc.queryForObject("select organizer_id from activities where id = ?", Long.class, future.id));
        assertEquals("CANCELLED", jdbc.queryForObject("select status from registrations where id = ?", String.class, waiting.id));
        assertEquals(0, jdbc.queryForObject("select registered_count from activities where id = ?", Integer.class, future.id));
        assertEquals("ACTIVE", jdbc.queryForObject("select status from registrations where id = ?", String.class, attended.id));
        assertNull(jdbc.queryForObject("select identity_id from registrations where id = ?", Long.class, attended.id));
        assertNotEquals(deletedId, jdbc.queryForObject("select student_id from checkins where id = ?", Long.class, checkin.id));
        assertEquals("REJECTED", jdbc.queryForObject("select status from binding_appeals where id = ?", String.class, appeal.id));
        assertEquals(1L, jdbc.queryForObject("select count(*) from user_security_audits where action = 'USER_DELETED' and actor_id = ?", Long.class, admin.id));
    }
    private Activity activity(UserAccount owner, boolean future) {
        Activity activity = new Activity();
        activity.title = "[ROLLBACK] 删除用户关联测试"; activity.description = "事务回滚测试"; activity.location = "测试地点";
        activity.organizer = owner; activity.capacity = 10; activity.registeredCount = 1;
        activity.startsAt = Instant.now().plusSeconds(future ? 7200 : -7200);
        activity.endsAt = activity.startsAt.plusSeconds(3600);
        activity.registrationOpensAt = activity.startsAt.minusSeconds(86400);
        activity.registrationClosesAt = activity.startsAt.minusSeconds(1800);
        activity.checkinOpensAt = activity.startsAt.minusSeconds(1800);
        activity.checkinClosesAt = activity.startsAt.plusSeconds(1800);
        return activities.saveAndFlush(activity);
    }
}

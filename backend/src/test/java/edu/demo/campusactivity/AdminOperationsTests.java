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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin_operations;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdminOperationsTests {
    @Autowired UserRepository users;
    @Autowired ActivityRepository activityRepository;
    @Autowired RegistrationRepository registrationRepository;
    @Autowired ActivityAuditRepository audits;
    @Autowired AdminController adminController;
    @Autowired OrganizerController organizerController;
    @Autowired ActivityService activities;
    @Autowired RegistrationService registrations;
    @Autowired CheckinService checkins;
    @Autowired BindingAppealService bindingAppeals;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void unbindRemainsDisabledUntilAppealWorkflowIsReady() {
        UserAccount admin = user("解绑开关管理员", UserAccount.Role.ADMIN);
        UserAccount student = user("解绑开关学生", UserAccount.Role.STUDENT);
        authenticate(admin);
        ApiException error = assertThrows(ApiException.class,
            () -> adminController.unbindWechat(student.id,
                new AdminController.UnbindRequest("学生申请核实绑定")));
        assertEquals(503, error.status.value());
        ApiException appealError = assertThrows(ApiException.class,
            () -> bindingAppeals.challenge("openid-disabled"));
        assertEquals(503, appealError.status.value());
        assertEquals(false, bindingAppeals.enabled());
    }

    @Test
    void adminAuditIsIdenticalAcrossLegalEntryPointsWithoutDuplicates() {
        UserAccount admin = user("审计管理员", UserAccount.Role.ADMIN);
        UserAccount organizer = user("审计组织者", UserAccount.Role.ORGANIZER);
        authenticate(admin);
        ActivityView viaAdmin = adminController.create(input("后台入口"));
        ActivityView viaOrganizer = organizerController.create(input("组织者入口"));
        assertActions(viaAdmin.id(), ActivityAudit.Action.CREATED);
        assertActions(viaOrganizer.id(), ActivityAudit.Action.CREATED);

        adminController.edit(viaAdmin.id(), input("后台已编辑"));
        organizerController.edit(viaOrganizer.id(), input("另一入口已编辑"));
        assertEquals("另一入口已编辑", activityRepository.findById(viaOrganizer.id()).orElseThrow().title);
        assertActions(viaAdmin.id(), ActivityAudit.Action.EDITED, ActivityAudit.Action.CREATED);
        assertActions(viaOrganizer.id(), ActivityAudit.Action.EDITED, ActivityAudit.Action.CREATED);

        adminController.publish(viaAdmin.id());
        adminController.publish(viaOrganizer.id());
        adminController.token(viaAdmin.id());
        organizerController.token(viaOrganizer.id());
        assertActions(viaAdmin.id(), ActivityAudit.Action.QR_ISSUED,
            ActivityAudit.Action.DIRECT_PUBLISHED, ActivityAudit.Action.EDITED, ActivityAudit.Action.CREATED);
        assertActions(viaOrganizer.id(), ActivityAudit.Action.QR_ISSUED,
            ActivityAudit.Action.DIRECT_PUBLISHED, ActivityAudit.Action.EDITED, ActivityAudit.Action.CREATED);
        assertEquals(2, adminController.activityAudits().stream()
            .filter(row -> row.action() == ActivityAudit.Action.QR_ISSUED).count());

        authenticate(organizer);
        assertThrows(ApiException.class, () -> adminController.create(input("无权")));
        assertThrows(ApiException.class, () -> adminController.activityAudits());
        assertThrows(ApiException.class, () -> organizerController.edit(viaAdmin.id(), input("越权")));
        assertThrows(ApiException.class, () -> organizerController.token(viaAdmin.id()));
    }

    @Test
    void cancelledRegistrationsAreExcludedAndManualDetailsStayManagementOnly() {
        UserAccount organizer = user("名单组织者", UserAccount.Role.ORGANIZER);
        UserAccount admin = user("名单管理员", UserAccount.Role.ADMIN);
        UserAccount first = user("名单学生一", UserAccount.Role.STUDENT);
        UserAccount second = user("名单学生二", UserAccount.Role.STUDENT);
        authenticate(organizer);
        ActivityView draft = activities.create(input("人数核对"));
        activities.submit(draft.id());
        authenticate(admin);
        activities.review(draft.id(), true, "");
        authenticate(first);
        registrations.register(draft.id());
        authenticate(second);
        registrations.register(draft.id());
        registrations.cancel(draft.id());

        authenticate(admin);
        assertEquals(1, checkins.adminStats(draft.id()).registeredCount());
        assertEquals(1, registrations.forAdmin(draft.id()).stream()
            .filter(row -> row.status() == Registration.Status.ACTIVE).count());
        assertEquals(1, activityRepository.findById(draft.id()).orElseThrow().registeredCount);
        assertEquals(1, registrationRepository.findByActivityIdOrderByRegisteredAtAsc(draft.id()).stream()
            .filter(row -> row.status == Registration.Status.ACTIVE).count());

        authenticate(second);
        registrations.register(draft.id());
        authenticate(organizer);
        RegistrationView manual = checkins.manual(draft.id(), first.getId(), "现场名单核验");
        assertNull(manual.manualCheckinReason());
        RegistrationView managed = registrations.forActivity(draft.id()).stream()
            .filter(row -> row.studentId().equals(first.getId())).findFirst().orElseThrow();
        assertEquals("现场名单核验", managed.manualCheckinReason());
        assertEquals(organizer.getName(), managed.checkinOperatorName());
        assertNotNull(managed.checkedAt());
        assertEquals(Checkin.Method.MANUAL, managed.checkinMethod());
        assertEquals(draft.id(), managed.activityId());
        authenticate(admin);
        assertEquals("现场名单核验", registrations.forAdmin(draft.id()).stream()
            .filter(row -> row.studentId().equals(first.getId())).findFirst().orElseThrow().manualCheckinReason());
        assertEquals(2, checkins.adminStats(draft.id()).registeredCount());
        assertEquals(1, checkins.adminStats(draft.id()).checkedCount());
        authenticate(first);
        RegistrationView personal = registrations.mine().get(0);
        assertNull(personal.manualCheckinReason());
        assertNull(personal.checkinOperatorName());
        assertThrows(ApiException.class, () -> registrations.forAdmin(draft.id()));
        assertThrows(ApiException.class, () -> registrations.forActivity(draft.id()));
    }

    private void assertActions(Long id, ActivityAudit.Action... expected) {
        assertEquals(List.of(expected), audits.findByActivityIdOrderByIdDesc(id).stream()
            .map(event -> event.action).toList());
    }

    private ActivityService.ActivityInput input(String title) {
        Instant now = Instant.now();
        return new ActivityService.ActivityInput(title, "活动说明", "导语", null, "报告厅", 108.93, 34.34,
            now.plusSeconds(3600), now.plusSeconds(7200), now.minusSeconds(60), now.plusSeconds(1800),
            now.minusSeconds(30), now.plusSeconds(3600), 20);
    }

    private UserAccount user(String name, UserAccount.Role role) {
        return users.saveAndFlush(new UserAccount(name, name + "@stage3.test", "unused", role));
    }

    private void authenticate(UserAccount user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
    }
}

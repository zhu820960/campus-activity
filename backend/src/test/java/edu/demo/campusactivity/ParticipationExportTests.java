package edu.demo.campusactivity;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:participation_exports;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp"
})
class ParticipationExportTests {
    @Autowired UserRepository users;
    @Autowired ActivityService activities;
    @Autowired RegistrationRepository registrations;
    @Autowired RegistrationService registrationService;
    @Autowired CheckinRepository checkins;
    @Autowired ParticipationExportService exports;
    @Autowired ParticipationExportAuditRepository audits;
    @Autowired ParticipationExportController controller;
    @Autowired AdminParticipationExportController adminController;
    @Autowired ParticipationExportAuditSearch auditSearch;
    @Autowired ParticipationExportAuditController auditController;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(UserAccount user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user.id, null, List.of()));
    }

    private UserAccount organizer(String suffix) {
        UserAccount user = new UserAccount("组织者" + suffix, "export-org-" + suffix + "@test.local",
            "unused", UserAccount.Role.ORGANIZER);
        user.roles.add(UserAccount.Role.STUDENT);
        return users.saveAndFlush(user);
    }

    private Long activity(UserAccount organizer) {
        as(organizer);
        Instant now = Instant.now();
        return activities.create(new ActivityService.ActivityInput("心理辅导", "说明", "导语",
            null, "报告厅", 108.93, 34.34, now.plusSeconds(7200), now.plusSeconds(10800),
            now.minusSeconds(60), now.plusSeconds(3600), now.plusSeconds(7100),
            now.plusSeconds(9000), 20)).id();
    }

    @Test
    void exportsOnlyOwnedActiveRowsAndMatchingCheckinsWithAudit() throws Exception {
        UserAccount organizer = organizer("owner");
        Long id = activity(organizer);
        Activity activity = activities.find(id);
        UserAccount checked = users.saveAndFlush(new UserAccount("=危险姓名", "export-checked@test.local",
            "unused", UserAccount.Role.STUDENT));
        checked.studentNumber = "20260000000001";
        checked = users.saveAndFlush(checked);
        UserAccount absent = users.saveAndFlush(new UserAccount("未签到", "export-absent@test.local",
            "unused", UserAccount.Role.STUDENT));
        UserAccount cancelled = users.saveAndFlush(new UserAccount("已取消", "export-cancelled@test.local",
            "unused", UserAccount.Role.STUDENT));
        Registration checkedRegistration = new Registration(activity, checked);
        checkedRegistration.identityId = 20L;
        checkedRegistration.bindingId = 30L;
        registrations.saveAndFlush(checkedRegistration);
        registrations.saveAndFlush(new Registration(activity, absent));
        Registration cancelledRegistration = new Registration(activity, cancelled);
        cancelledRegistration.status = Registration.Status.CANCELLED;
        registrations.saveAndFlush(cancelledRegistration);
        Checkin checkin = new Checkin(activity, checked, organizer, Checkin.Method.MANUAL, "现场核验");
        checkin.identityId = 20L;
        checkin.bindingId = 30L;
        checkins.saveAndFlush(checkin);

        as(organizer);
        long auditsBefore = audits.count();
        byte[] registrationFile = exports.organizer(id, ParticipationExportService.ListType.REGISTRATIONS);
        byte[] checkinFile = exports.organizer(id, ParticipationExportService.ListType.CHECKINS);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(registrationFile))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(3, sheet.getPhysicalNumberOfRows());
            assertEquals("活动名称", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("心理辅导", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("20260000000001", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("'=危险姓名", sheet.getRow(1).getCell(5).getStringCellValue());
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(checkinFile))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getPhysicalNumberOfRows());
            assertTrue(sheet.getRow(1).getCell(6).getStringCellValue().length() >= 16);
        }
        assertEquals(auditsBefore + 2, audits.count());
        assertTrue(audits.findAll().stream().anyMatch(audit -> audit.activity.getId().equals(id)
            && audit.rowCount == 2 && audit.filtersJson.contains("REGISTRATIONS")));
        assertTrue(audits.findAll().stream().anyMatch(audit -> audit.activity.getId().equals(id)
            && audit.rowCount == 1 && audit.filtersJson.contains("CHECKINS")));
    }

    @Test
    void rejectsOtherOrganizerStudentAndAdminWithoutAudit() {
        UserAccount owner = organizer("restricted-owner");
        Long id = activity(owner);
        UserAccount other = organizer("restricted-other");
        UserAccount student = users.saveAndFlush(new UserAccount("学生", "export-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "export-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        long before = audits.count();
        as(other);
        assertThrows(ApiException.class, () -> exports.organizer(id, ParticipationExportService.ListType.REGISTRATIONS));
        as(student);
        assertThrows(ApiException.class, () -> exports.organizer(id, ParticipationExportService.ListType.REGISTRATIONS));
        as(admin);
        assertThrows(ApiException.class, () -> exports.organizer(id, ParticipationExportService.ListType.REGISTRATIONS));
        assertEquals(before, audits.count());
    }

    @Test
    void mismatchedAttributionDoesNotAppearInCheckinExport() throws Exception {
        UserAccount owner = organizer("mismatch");
        Long id = activity(owner);
        Activity activity = activities.find(id);
        UserAccount student = users.saveAndFlush(new UserAccount("学生", "export-mismatch@test.local",
            "unused", UserAccount.Role.STUDENT));
        Registration registration = new Registration(activity, student);
        registration.identityId = 11L;
        registration.bindingId = 12L;
        registrations.saveAndFlush(registration);
        Checkin checkin = new Checkin(activity, student, owner, Checkin.Method.MANUAL, "旧来源");
        checkin.identityId = 13L;
        checkin.bindingId = 14L;
        checkins.saveAndFlush(checkin);
        as(owner);
        byte[] file = exports.organizer(id, ParticipationExportService.ListType.CHECKINS);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertEquals(1, workbook.getSheetAt(0).getPhysicalNumberOfRows());
        }
        as(student);
        assertEquals(null, registrationService.mine().stream()
            .filter(row -> row.activityId().equals(id)).findFirst().orElseThrow().checkedAt());
        as(owner);
        assertEquals(null, registrationService.forActivity(id).stream()
            .filter(row -> row.activityId().equals(id)).findFirst().orElseThrow().checkedAt());
    }

    @Test
    void downloadResponseIsAttachmentAndCannotBeCached() {
        UserAccount owner = organizer("headers");
        Long id = activity(owner);
        as(owner);
        var response = controller.registrations(id);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentDisposition().isAttachment());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertTrue(response.getBody().length > 100);
    }

    @Test
    void adminMayExportAcrossActivitiesButOrganizerCannotUseAdminRoute() {
        UserAccount owner = organizer("admin-route-owner");
        Long id = activity(owner);
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "export-admin-route@test.local",
            "unused", UserAccount.Role.ADMIN));
        as(owner);
        assertThrows(ApiException.class, () -> adminController.registrations(id));
        long before = audits.count();
        as(admin);
        var response = adminController.checkins(id);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(before + 1, audits.count());
        assertTrue(audits.findAll().stream().anyMatch(audit -> audit.activity.getId().equals(id)
            && audit.actor.getId().equals(admin.id) && "ADMIN".equals(audit.scope)));
    }

    @Test
    void exportAuditIsPagedAndAdministratorOnly() {
        UserAccount owner = organizer("audit-owner");
        Long id = activity(owner);
        as(owner);
        exports.organizer(id, ParticipationExportService.ListType.REGISTRATIONS);
        assertThrows(ApiException.class, () -> auditSearch.search(0, 20, id, null, null, null));

        UserAccount admin = users.saveAndFlush(new UserAccount("审计管理员", "export-audit-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        as(admin);
        var result = auditController.list(0, 1, id, owner.name, null, null);
        assertEquals(1, result.total());
        assertEquals(1, result.items().size());
        assertEquals(id, result.items().get(0).activityId());
        assertEquals(owner.name, result.items().get(0).actorName());
        assertEquals("心理辅导", result.items().get(0).activityTitle());
        assertEquals("ORGANIZER", result.items().get(0).scope());
        assertEquals("REGISTRATIONS", result.items().get(0).listType());
        assertEquals(0, result.items().get(0).rowCount());
        assertTrue(auditController.list(0, 20, id, "no match", null, null).items().isEmpty());
        assertThrows(ApiException.class, () -> auditSearch.search(0, 101, null, null, null, null));
        assertThrows(ApiException.class, () -> auditSearch.search(0, 20, null, null,
            LocalDate.now(), LocalDate.now().minusDays(1)));
    }

    @Test
    void registrationExportContinuesBeyondFirstPage() throws Exception {
        UserAccount owner = organizer("paged-owner");
        Long id = activity(owner);
        Activity event = activities.find(id);
        java.util.ArrayList<UserAccount> students = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) {
            students.add(new UserAccount("学生" + i, "export-paged-" + i + "@test.local",
                "unused", UserAccount.Role.STUDENT));
        }
        students = new java.util.ArrayList<>(users.saveAllAndFlush(students));
        java.util.ArrayList<Registration> rows = new java.util.ArrayList<>();
        for (UserAccount student : students) rows.add(new Registration(event, student));
        registrations.saveAllAndFlush(rows);

        as(owner);
        byte[] file = exports.organizer(id, ParticipationExportService.ListType.REGISTRATIONS);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertEquals(502, workbook.getSheetAt(0).getPhysicalNumberOfRows());
        }
        assertTrue(audits.findAll().stream().anyMatch(audit -> audit.activity.getId().equals(id)
            && audit.rowCount == 501 && audit.filtersJson.contains("REGISTRATIONS")));
    }
}

package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:personal_pdf;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp"
})
class PersonalParticipationPdfTests {
    @Autowired UserRepository users;
    @Autowired ActivityService activities;
    @Autowired RegistrationRepository registrations;
    @Autowired CheckinRepository checkins;
    @Autowired PersonalParticipationPdfService service;
    @Autowired PersonalParticipationPdfController controller;
    @Autowired ParticipationExportAuditRepository audits;
    @Autowired WechatIdentityRepository identities;
    @Autowired WechatStudentBindingRepository bindings;
    @Autowired MiniappAccessTokenRepository tokens;
    @Autowired WebApplicationContext context;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    private void as(UserAccount user, Long identityId, Long bindingId) {
        var authentication = new UsernamePasswordAuthenticationToken(user.id, null, List.of());
        if (identityId != null) {
            authentication.setDetails(new MiniappAttribution(user.id, identityId, bindingId));
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Activity activity(UserAccount organizer, String title) {
        as(organizer, null, null);
        Instant now = Instant.now();
        Long id = activities.create(new ActivityService.ActivityInput(title, "说明", "导语",
            null, "西安欧亚学院", 108.93, 34.34, now.plusSeconds(7200), now.plusSeconds(10800),
            now.minusSeconds(60), now.plusSeconds(3600), now.plusSeconds(7100),
            now.plusSeconds(9000), 20)).id();
        return activities.find(id);
    }

    @Test
    void pdfContainsOnlyCurrentIdentityCheckedRecordsAndAudit() throws Exception {
        UserAccount organizer = users.saveAndFlush(new UserAccount("组织者", "pdf-organizer@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        UserAccount student = users.saveAndFlush(new UserAccount("学生甲", "pdf-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        student.studentNumber = "20260000000001";
        users.saveAndFlush(student);
        Activity included = activity(organizer, "心理辅导");
        Activity excluded = activity(organizer, "篮球赛");
        Registration includedRow = new Registration(included, student);
        includedRow.identityId = 11L;
        includedRow.bindingId = 21L;
        registrations.saveAndFlush(includedRow);
        Checkin includedCheckin = new Checkin(included, student, organizer, Checkin.Method.MANUAL, "现场核验");
        includedCheckin.identityId = 11L;
        includedCheckin.bindingId = 21L;
        checkins.saveAndFlush(includedCheckin);
        Registration excludedRow = new Registration(excluded, student);
        excludedRow.identityId = 11L;
        excludedRow.bindingId = 21L;
        registrations.saveAndFlush(excludedRow);
        Checkin otherSource = new Checkin(excluded, student, organizer, Checkin.Method.MANUAL, "来源不一致");
        otherSource.identityId = 12L;
        otherSource.bindingId = 22L;
        checkins.saveAndFlush(otherSource);

        as(student, 11L, 21L);
        byte[] bytes = service.export("心理");
        java.nio.file.Files.write(java.nio.file.Path.of("target/personal-participation-sample.pdf"), bytes);
        try (var document = Loader.loadPDF(bytes)) {
            String content = new PDFTextStripper().getText(document);
            assertTrue(content.contains("心理辅导"));
            assertTrue(content.contains("西安欧亚学院"), content);
            assertTrue(content.contains("20260000000001"));
            assertTrue(content.contains("非官方"));
            assertTrue(!content.contains("篮球赛"));
        }
        assertTrue(audits.findAll().stream().anyMatch(audit -> audit.actor.getId().equals(student.id)
            && "PDF".equals(audit.format) && audit.rowCount == 1));
        var response = controller.export(null);
        assertTrue(response.getHeaders().getContentDisposition().isAttachment());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));

        as(student, 12L, 22L);
        try (var document = Loader.loadPDF(service.export(null))) {
            String content = new PDFTextStripper().getText(document);
            assertTrue(content.contains("暂无符合条件的已签到活动"));
            assertTrue(!content.contains("心理辅导"));
        }
    }

    @Test
    void requiresBoundMiniappIdentityAndRejectsLongSearch() {
        UserAccount student = users.saveAndFlush(new UserAccount("学生乙", "pdf-student2@test.local",
            "unused", UserAccount.Role.STUDENT));
        as(student, null, null);
        assertThrows(ApiException.class, () -> service.export(null));
        as(student, 31L, 41L);
        assertThrows(ApiException.class, () -> service.export("x".repeat(81)));
        assertEquals(0, audits.findAll().stream()
            .filter(audit -> audit.actor.getId().equals(student.id)).count());
    }

    @Test
    void paginatesTenCheckedActivitiesWithoutDroppingTheLastOne() throws Exception {
        UserAccount organizer = users.saveAndFlush(new UserAccount("分页组织者", "pdf-pages-org@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        UserAccount student = users.saveAndFlush(new UserAccount("分页学生", "pdf-pages-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        for (int i = 0; i < 10; i++) {
            Activity event = activity(organizer, "分页活动" + i);
            Registration row = new Registration(event, student);
            row.identityId = 51L;
            row.bindingId = 61L;
            registrations.saveAndFlush(row);
            Checkin checkin = new Checkin(event, student, organizer, Checkin.Method.MANUAL, "现场核验");
            checkin.identityId = 51L;
            checkin.bindingId = 61L;
            checkins.saveAndFlush(checkin);
        }
        as(student, 51L, 61L);
        byte[] bytes = service.export("分页活动");
        java.nio.file.Files.write(java.nio.file.Path.of("target/personal-participation-pages.pdf"), bytes);
        try (var document = Loader.loadPDF(bytes)) {
            assertEquals(2, document.getNumberOfPages());
            String content = new PDFTextStripper().getText(document);
            assertTrue(content.contains("分页活动0"));
            assertTrue(content.contains("分页活动9"));
            assertTrue(content.contains("2 / 2"));
        }
    }

    @Test
    void httpDownloadRequiresActiveBoundBearerAndWritesAudit() throws Exception {
        UserAccount student = users.saveAndFlush(new UserAccount("路由学生", "pdf-http-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        WechatIdentity identity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "pdf-http-openid"));
        WechatStudentBinding binding = bindings.saveAndFlush(new WechatStudentBinding(identity, student));
        String raw = "a".repeat(43);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        MiniappAccessToken token = tokens.saveAndFlush(new MiniappAccessToken(binding, hash, Instant.now()));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();

        mvc.perform(get("/api/miniapp/my/participation.pdf")).andExpect(status().isUnauthorized());
        byte[] bytes = mvc.perform(get("/api/miniapp/my/participation.pdf")
                .header("Authorization", "Bearer " + raw))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var document = Loader.loadPDF(bytes)) {
            assertTrue(new PDFTextStripper().getText(document).contains("暂无符合条件的已签到活动"));
        }
        assertTrue(audits.findAll().stream().anyMatch(audit -> audit.actor.getId().equals(student.id)
            && "PDF".equals(audit.format) && audit.rowCount == 0));
        token.revokedAt = Instant.now();
        tokens.saveAndFlush(token);
        mvc.perform(get("/api/miniapp/my/participation.pdf")
                .header("Authorization", "Bearer " + raw))
            .andExpect(status().isUnauthorized());
    }
}

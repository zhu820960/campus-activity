package edu.demo.campusactivity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:binding_appeals;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp",
    "app.appeals.enabled=true",
    "app.appeals.directory=./target/test-private-appeals"
})
class BindingAppealIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired BindingAppealRepository appeals;
    @Autowired AppealChallengeRepository challenges;
    @Autowired BindingAppealEvidenceCleanup cleanup;
    @Autowired WechatIdentityRepository identities;
    @Autowired WechatStudentBindingRepository bindings;
    @Autowired MiniappAuthService auth;
    @Autowired ActivityRepository activities;
    @Autowired RegistrationRepository registrations;
    @Autowired CheckinRepository checkins;
    @Autowired ParticipationAttributionReviewRepository attributionReviews;
    @MockitoBean WechatCodeExchange wechat;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void unboundIdentityCanSubmitPrivateEvidenceAndAdminCanReject() throws Exception {
        mvc().perform(get("/api/miniapp/auth/appeals/availability"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        UserAccount occupied = users.saveAndFlush(UserAccount.selfRegisteredStudent("原账号", "20260000000001"));
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "appeal-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        when(wechat.exchange("appeal-code")).thenReturn("openid-appeal");
        String challengeBody = mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"appeal-code\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new ObjectMapper().readTree(challengeBody).path("token").asText();
        assertEquals(43, token.length());
        assertNotEquals(token, challenges.findAll().get(0).tokenHash);

        byte[] photo = photo();
        mvc().perform(multipart("/api/miniapp/auth/appeals/evidence")
                .file(new MockMultipartFile("file", "card.png", "image/png", photo))
                .header("X-Appeal-Challenge", token)).andExpect(status().isOk());
        mvc().perform(multipart("/api/miniapp/auth/appeals/evidence")
                .file(new MockMultipartFile("file", "card.png", "image/png", photo))
                .header("X-Appeal-Challenge", token)).andExpect(status().isConflict());
        String submitted = mvc().perform(post("/api/miniapp/auth/appeals")
                .header("X-Appeal-Challenge", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"STUDENT_NUMBER_OCCUPIED\",\"proposedStudentNumber\":\"20260000000001\",\"reason\":\"学号被他人绑定，请核对学生证\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn().getResponse().getContentAsString();
        long appealId = new ObjectMapper().readTree(submitted).path("id").asLong();
        assertTrue(appeals.existsById(appealId));
        assertEquals(occupied.id, users.findByStudentNumber("20260000000001").orElseThrow().id);
        assertTrue(appeals.findById(appealId).orElseThrow().evidenceKey.matches("[0-9a-f]{32}\\.png"));
        mvc().perform(post("/api/miniapp/auth/appeals")
                .header("X-Appeal-Challenge", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"STUDENT_NUMBER_OCCUPIED\",\"proposedStudentNumber\":\"20260000000001\",\"reason\":\"重复\"}"))
            .andExpect(status().isUnauthorized());
        mvc().perform(get("/api/admin/binding-appeals/" + appealId + "/evidence"))
            .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"appeal-code\"}"))
            .andExpect(status().isConflict());

        MockHttpSession session = (MockHttpSession) mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"appeal-admin@test.local\",\"password\":\"adminpass123\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc().perform(get("/api/admin/binding-appeals/availability").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc().perform(get("/api/admin/binding-appeals/pending").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem((int) appealId)));
        mvc().perform(get("/api/admin/binding-appeals/" + appealId + "/evidence").session(session))
            .andExpect(status().isOk());
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/reject")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"证据不清晰，请重新上传\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mvc().perform(get("/api/admin/binding-appeals/pending").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", not(hasItem((int) appealId))));
        mvc().perform(post("/api/miniapp/auth/appeals/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"appeal-code\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.reviewNote").value("证据不清晰，请重新上传"));
        when(wechat.exchange("other-code")).thenReturn("openid-other-applicant");
        mvc().perform(post("/api/miniapp/auth/appeals/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"other-code\"}"))
            .andExpect(status().isNotFound());
        BindingAppeal finished = appeals.findById(appealId).orElseThrow();
        finished.reviewedAt = Instant.now().minusSeconds(31L * 24 * 3600);
        appeals.saveAndFlush(finished);
        cleanup.removeExpiredEvidence();
        assertTrue(appeals.findById(appealId).orElseThrow().evidenceDeletedAt != null);
        mvc().perform(get("/api/admin/binding-appeals/" + appealId + "/evidence").session(session))
            .andExpect(status().isNotFound());
        mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"appeal-code\"}"))
            .andExpect(status().isOk());
        assertEquals(admin.id, appeals.findById(appealId).orElseThrow().reviewer.getId());
    }

    @Test
    void wrongStudentNumberRequiresExistingBindingAndNoBareEvidenceAccess() throws Exception {
        when(wechat.exchange("wrong-code")).thenReturn("openid-wrong");
        String body = mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"wrong-code\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new ObjectMapper().readTree(body).path("token").asText();
        mvc().perform(post("/api/miniapp/auth/appeals")
                .header("X-Appeal-Challenge", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"WRONG_STUDENT_NUMBER\",\"proposedStudentNumber\":\"20260000000002\",\"reason\":\"绑错学号\"}"))
            .andExpect(status().isBadRequest());
        mvc().perform(multipart("/api/miniapp/auth/appeals/evidence")
                .file(new MockMultipartFile("file", "card.png", "image/png", photo()))
                .header("X-Appeal-Challenge", token)).andExpect(status().isOk());
        mvc().perform(post("/api/miniapp/auth/appeals")
                .header("X-Appeal-Challenge", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"WRONG_STUDENT_NUMBER\",\"proposedStudentNumber\":\"20260000000002\",\"reason\":\"绑错学号\"}"))
            .andExpect(status().isConflict());
        Long identityId = identities.findByAppIdAndOpenId("test-miniapp", "openid-wrong").orElseThrow().id;
        assertTrue(!appeals.existsByApplicantIdentityIdAndStatus(identityId, BindingAppeal.Status.PENDING));
    }

    @Test
    void boundIdentityCanAppealWrongStudentNumberWithoutChangingBinding() throws Exception {
        UserAccount current = users.saveAndFlush(UserAccount.selfRegisteredStudent("误绑定学生", "20260000000003"));
        current.email = "wrong-bound@test.local";
        current.passwordHash = passwords.encode("student123");
        users.saveAndFlush(current);
        auth.bind("openid-bound-wrong", "wrong-bound@test.local", "student123");
        when(wechat.exchange("bound-wrong-code")).thenReturn("openid-bound-wrong");
        String body = mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"bound-wrong-code\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new ObjectMapper().readTree(body).path("token").asText();
        mvc().perform(multipart("/api/miniapp/auth/appeals/evidence")
                .file(new MockMultipartFile("file", "card.png", "image/png", photo()))
                .header("X-Appeal-Challenge", token)).andExpect(status().isOk());
        mvc().perform(post("/api/miniapp/auth/appeals")
                .header("X-Appeal-Challenge", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"WRONG_STUDENT_NUMBER\",\"proposedStudentNumber\":\"20260000000003\",\"reason\":\"目标学号与原学号相同\"}"))
            .andExpect(status().isBadRequest());
        mvc().perform(post("/api/miniapp/auth/appeals")
                .header("X-Appeal-Challenge", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"WRONG_STUDENT_NUMBER\",\"proposedStudentNumber\":\"20260000000004\",\"reason\":\"原学号不是本人学号\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentStudentId").value(current.id));
        assertEquals(current.id, auth.login("openid-bound-wrong").user().id());
    }

    @Test
    void impactPreviewSeparatesConfirmedUnknownAndTargetConflict() throws Exception {
        UserAccount source = users.saveAndFlush(UserAccount.selfRegisteredStudent("申请人原账号", "20260000000005"));
        source.email = "impact-source@test.local";
        source.passwordHash = passwords.encode("student123");
        users.saveAndFlush(source);
        UserAccount target = users.saveAndFlush(UserAccount.selfRegisteredStudent("拟绑定账号", "20260000000006"));
        target.email = "impact-target@test.local";
        target.passwordHash = passwords.encode("student123");
        target.roles.add(UserAccount.Role.ORGANIZER);
        users.saveAndFlush(target);
        auth.bind("openid-existing-target", target.email, "student123");
        activity(target, "目标账号组织的活动");
        UserAccount admin = users.saveAndFlush(new UserAccount("影响预览管理员", "impact-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        MiniappAuthService.LoginResult login = auth.bind("openid-impact", "impact-source@test.local", "student123");
        MiniappAttribution attribution = auth.authenticateWithAttribution(login.token()).attribution();
        Activity confirmedActivity = activity(admin, "微信来源确定的活动");
        Activity unknownActivity = activity(admin, "历史来源未知的活动");
        Registration confirmed = new Registration(confirmedActivity, source);
        confirmed.identityId = attribution.identityId();
        confirmed.bindingId = attribution.bindingId();
        registrations.saveAndFlush(confirmed);
        Registration unknown = registrations.saveAndFlush(new Registration(unknownActivity, source));
        registrations.saveAndFlush(new Registration(unknownActivity, target));
        Checkin checkin = new Checkin(confirmedActivity, source, null, Checkin.Method.QR, null);
        checkin.identityId = attribution.identityId();
        checkin.bindingId = attribution.bindingId();
        checkins.saveAndFlush(checkin);
        Checkin mismatched = new Checkin(unknownActivity, source, null, Checkin.Method.QR, null);
        mismatched.identityId = attribution.identityId();
        mismatched.bindingId = attribution.bindingId();
        checkins.saveAndFlush(mismatched);

        when(wechat.exchange("impact-code")).thenReturn("openid-impact");
        String challengeBody = mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"impact-code\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String challenge = new ObjectMapper().readTree(challengeBody).path("token").asText();
        mvc().perform(multipart("/api/miniapp/auth/appeals/evidence")
                .file(new MockMultipartFile("file", "card.png", "image/png", photo()))
                .header("X-Appeal-Challenge", challenge)).andExpect(status().isOk());
        String appealBody = mvc().perform(post("/api/miniapp/auth/appeals")
                .header("X-Appeal-Challenge", challenge).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"WRONG_STUDENT_NUMBER\",\"proposedStudentNumber\":\"20260000000006\",\"reason\":\"学号应为目标账号\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long appealId = new ObjectMapper().readTree(appealBody).path("id").asLong();
        mvc().perform(get("/api/admin/binding-appeals/" + appealId + "/impact"))
            .andExpect(status().isUnauthorized());
        MockHttpSession session = (MockHttpSession) mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"impact-admin@test.local\",\"password\":\"adminpass123\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        String impactBody = mvc().perform(get("/api/admin/binding-appeals/" + appealId + "/impact")
                .session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.targetStudentId").value(target.id))
            .andExpect(jsonPath("$.requiresActivityOwnershipReview").value(true))
            .andExpect(jsonPath("$.accounts.length()").value(2))
            .andExpect(jsonPath("$.rows.length()").value(2))
            .andReturn().getResponse().getContentAsString();
        var accounts = new ObjectMapper().readTree(impactBody).path("accounts");
        assertEquals(target.id.longValue(), accounts.get(1).path("studentId").asLong());
        assertEquals(1, accounts.get(1).path("activityCount").asLong());
        assertEquals("目标账号组织的活动", accounts.get(1).path("ownedActivities").get(0).path("title").asText());
        assertEquals("DRAFT", accounts.get(1).path("ownedActivities").get(0).path("status").asText());
        assertTrue(accounts.get(1).path("organizer").asBoolean());
        assertTrue(accounts.get(1).path("webCredentialsActive").asBoolean());
        assertTrue(accounts.get(1).path("boundToOtherIdentity").asBoolean());
        var rows = new ObjectMapper().readTree(impactBody).path("rows");
        for (var row : rows) {
            if (row.path("registrationId").asLong() == confirmed.id) {
                assertEquals("CONFIRMED_IDENTITY", row.path("source").asText());
                assertEquals(checkin.id.longValue(), row.path("checkinId").asLong());
                assertEquals(false, row.path("targetHasRegistration").asBoolean());
            } else if (row.path("registrationId").asLong() == unknown.id) {
                assertEquals("UNKNOWN_HISTORICAL", row.path("source").asText());
                assertEquals(true, row.path("targetHasRegistration").asBoolean());
                assertTrue(row.path("checkinId").isNull());
            } else throw new AssertionError("Unexpected participation row");
        }
        assertEquals(source.id, registrations.findById(confirmed.id).orElseThrow().student.getId());
        assertEquals(source.id, registrations.findById(unknown.id).orElseThrow().student.getId());
    }

    @Test
    void reviewedAppealsRemainSearchableWithStablePaginationAndPrivateFieldsExcluded() throws Exception {
        UserAccount admin = users.saveAndFlush(new UserAccount("历史审核管理员", "appeal-history@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        Instant reviewedAt = Instant.now().minusSeconds(3600);
        BindingAppeal[] history = new BindingAppeal[2];
        for (int index = 0; index < 3; index++) {
            WechatIdentity identity = identities.saveAndFlush(new WechatIdentity("test-miniapp", "history-" + index));
            BindingAppeal appeal = new BindingAppeal(identity, null, BindingAppeal.Type.STUDENT_NUMBER_OCCUPIED,
                "20269999990001", "请核对学生证", "0123456789abcdef0123456789abcdef.png", reviewedAt);
            if (index < 2) {
                appeal.status = BindingAppeal.Status.REJECTED;
                appeal.reviewer = admin;
                appeal.reviewedAt = reviewedAt;
                appeal.reviewNote = "照片不清晰";
                if (index == 0) appeal.evidenceDeletedAt = Instant.now();
                history[index] = appeals.saveAndFlush(appeal);
            } else appeals.saveAndFlush(appeal);
        }
        mvc().perform(get("/api/admin/binding-appeals")).andExpect(status().isUnauthorized());
        users.saveAndFlush(new UserAccount("普通组织者", "appeal-history-organizer@test.local",
            passwords.encode("organizer123"), UserAccount.Role.ORGANIZER));
        MockHttpSession organizerSession = (MockHttpSession) mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"appeal-history-organizer@test.local\",\"password\":\"organizer123\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc().perform(get("/api/admin/binding-appeals").session(organizerSession))
            .andExpect(status().isForbidden());
        MockHttpSession session = (MockHttpSession) mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"appeal-history@test.local\",\"password\":\"adminpass123\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc().perform(get("/api/admin/binding-appeals").session(session)
                .param("queue", "REVIEWED").param("studentNumber", "20269999990001").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
            .andExpect(jsonPath("$.items[0].id").value(history[1].id))
            .andExpect(jsonPath("$.items[0].reviewerName").value("历史审核管理员"))
            .andExpect(jsonPath("$.items[0].reviewNote").value("照片不清晰"))
            .andExpect(jsonPath("$.items[0].evidenceAvailable").value(true))
            .andExpect(jsonPath("$.items[0].evidenceKey").doesNotExist())
            .andExpect(jsonPath("$.items[0].applicantIdentity").doesNotExist());
        mvc().perform(get("/api/admin/binding-appeals").session(session)
                .param("queue", "REVIEWED").param("studentNumber", "20269999990001")
                .param("size", "1").param("page", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(history[0].id))
            .andExpect(jsonPath("$.items[0].evidenceAvailable").value(false));
        mvc().perform(get("/api/admin/binding-appeals").session(session)
                .param("queue", "PENDING").param("studentNumber", "20269999990001"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].status").value("PENDING"));
        mvc().perform(get("/api/admin/binding-appeals").session(session).param("size", "101"))
            .andExpect(status().isBadRequest());
        mvc().perform(get("/api/admin/binding-appeals").session(session).param("page", "-1"))
            .andExpect(status().isBadRequest());
        mvc().perform(get("/api/admin/binding-appeals").session(session).param("studentNumber", "%"))
            .andExpect(status().isBadRequest());
        mvc().perform(get("/api/admin/binding-appeals").session(session).param("queue", "INVALID"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void approvalTransfersOnlyExplicitRowsAndRevokesOldBinding() throws Exception {
        UserAccount source = users.saveAndFlush(UserAccount.selfRegisteredStudent("误绑账号", "20268888880001"));
        source.email = "approve-source@test.local";
        source.passwordHash = passwords.encode("student123");
        users.saveAndFlush(source);
        UserAccount target = users.saveAndFlush(UserAccount.selfRegisteredStudent("真实学生", "20268888880002"));
        UserAccount admin = users.saveAndFlush(new UserAccount("批准管理员", "approve-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        MiniappAuthService.LoginResult oldLogin = auth.bind("openid-approve-source", source.email, "student123");
        MiniappAttribution oldAttribution = auth.authenticateWithAttribution(oldLogin.token()).attribution();
        Activity moveActivity = activity(admin, "需要转移的活动");
        Activity keepActivity = activity(admin, "决定保留的活动");
        Activity unrelatedActivity = activity(admin, "其他微信的活动");
        Registration move = new Registration(moveActivity, source);
        move.identityId = oldAttribution.identityId();
        move.bindingId = oldAttribution.bindingId();
        registrations.saveAndFlush(move);
        Registration keep = registrations.saveAndFlush(new Registration(keepActivity, source));
        WechatIdentity unrelatedIdentity = identities.saveAndFlush(new WechatIdentity(
            "test-miniapp", "openid-unrelated-history"));
        Registration unrelated = new Registration(unrelatedActivity, source);
        unrelated.identityId = unrelatedIdentity.getId();
        registrations.saveAndFlush(unrelated);
        Checkin checkin = new Checkin(moveActivity, source, null, Checkin.Method.QR, null);
        checkin.identityId = oldAttribution.identityId();
        checkin.bindingId = oldAttribution.bindingId();
        checkins.saveAndFlush(checkin);
        long appealId = submitAppeal("approve-code", "openid-approve-source", "WRONG_STUDENT_NUMBER",
            "20268888880002");
        MockHttpSession session = adminSession("approve-admin@test.local");
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifiedName\":\"真实学生\",\"reason\":\"资料已核对\","
                    + "\"replaceTargetBinding\":false,\"decisions\":[]}"))
            .andExpect(status().isConflict());
        assertEquals(source.id, registrations.findById(move.id).orElseThrow().student.getId());
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifiedName\":\"真实学生\",\"reason\":\"已核对学生证和本人说明\","
                    + "\"replaceTargetBinding\":false,\"decisions\":["
                    + "{\"registrationId\":" + move.id + ",\"decision\":\"TRANSFER\",\"reason\":\"签到与本微信来源一致\"},"
                    + "{\"registrationId\":" + keep.id + ",\"decision\":\"KEEP\",\"reason\":\"旧记录来源未知，保留原账号\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertEquals(target.id, registrations.findById(move.id).orElseThrow().student.getId());
        assertEquals(oldAttribution.identityId(), registrations.findById(move.id).orElseThrow().identityId);
        assertEquals(target.id, checkins.findById(checkin.id).orElseThrow().student.getId());
        assertEquals(source.id, registrations.findById(keep.id).orElseThrow().student.getId());
        assertEquals(source.id, registrations.findById(unrelated.id).orElseThrow().student.getId());
        assertEquals(2, attributionReviews.findByAppealIdOrderByRegistrationId(appealId).size());
        assertEquals(target.id, auth.login("openid-approve-source").user().id());
        assertThrows(ApiException.class, () -> auth.authenticate(oldLogin.token()));
        assertEquals(null, users.findById(source.id).orElseThrow().passwordHash);
        mvc().perform(get("/api/admin/binding-appeals/" + appealId + "/attribution-reviews")
                .session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].reviewerName").value("批准管理员"));
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifiedName\":\"真实学生\",\"reason\":\"重复批准\","
                    + "\"replaceTargetBinding\":false,\"decisions\":[]}"))
            .andExpect(status().isConflict());
    }

    @Test
    void approvalRefusesSilentReplacementAndTransferConflict() throws Exception {
        UserAccount source = users.saveAndFlush(UserAccount.selfRegisteredStudent("原微信", "20268888880003"));
        source.email = "approve-conflict-source@test.local";
        source.passwordHash = passwords.encode("student123");
        users.saveAndFlush(source);
        UserAccount target = users.saveAndFlush(UserAccount.selfRegisteredStudent("目标微信", "20268888880004"));
        target.email = "approve-conflict-target@test.local";
        target.passwordHash = passwords.encode("student123");
        users.saveAndFlush(target);
        UserAccount admin = users.saveAndFlush(new UserAccount("冲突管理员", "approve-conflict-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        MiniappAuthService.LoginResult sourceLogin = auth.bind("openid-conflict-source", source.email, "student123");
        MiniappAuthService.LoginResult targetLogin = auth.bind("openid-conflict-target", target.email, "student123");
        Activity activity = activity(admin, "双方都报名的活动");
        Registration sourceRow = registrations.saveAndFlush(new Registration(activity, source));
        registrations.saveAndFlush(new Registration(activity, target));
        long appealId = submitAppeal("conflict-code", "openid-conflict-source", "WRONG_STUDENT_NUMBER",
            "20268888880004");
        MockHttpSession session = adminSession("approve-conflict-admin@test.local");
        String transfer = "{\"verifiedName\":\"目标微信\",\"reason\":\"核验通过\","
            + "\"replaceTargetBinding\":true,\"decisions\":[{\"registrationId\":" + sourceRow.id
            + ",\"decision\":\"TRANSFER\",\"reason\":\"申请转移\"}]}";
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(transfer))
            .andExpect(status().isConflict());
        assertEquals(source.id, registrations.findById(sourceRow.id).orElseThrow().student.getId());
        assertEquals(source.id, auth.authenticate(sourceLogin.token()).getId());
        String keep = transfer.replace("TRANSFER", "KEEP");
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(keep.replace("\"replaceTargetBinding\":true", "\"replaceTargetBinding\":false")))
            .andExpect(status().isConflict());
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(keep))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThrows(ApiException.class, () -> auth.authenticate(targetLogin.token()));
        assertEquals(target.id, auth.login("openid-conflict-source").user().id());
        assertEquals(source.id, registrations.findById(sourceRow.id).orElseThrow().student.getId());
    }

    @Test
    void approvalCanCreateVerifiedStudentWithoutInheritingSourcePrivileges() throws Exception {
        UserAccount source = users.saveAndFlush(UserAccount.selfRegisteredStudent("历史学号", "20268888880005"));
        source.email = "approve-new-source@test.local";
        source.passwordHash = passwords.encode("student123");
        users.saveAndFlush(source);
        UserAccount admin = users.saveAndFlush(new UserAccount("新增账号管理员", "approve-new-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        MiniappAuthService.LoginResult oldLogin = auth.bind("openid-approve-new", source.email, "student123");
        long appealId = submitAppeal("new-code", "openid-approve-new", "WRONG_STUDENT_NUMBER",
            "20268888880006");
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifiedName\":\"新学号学生\",\"reason\":\"已核对\","
                    + "\"replaceTargetBinding\":false,\"decisions\":[]}"))
            .andExpect(status().isUnauthorized());
        MockHttpSession session = adminSession("approve-new-admin@test.local");
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifiedName\":\"新学号学生\",\"reason\":\"已核对学生证\","
                    + "\"replaceTargetBinding\":false,\"decisions\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        UserAccount created = users.findByStudentNumber("20268888880006").orElseThrow();
        assertEquals("新学号学生", created.name);
        assertEquals(java.util.Set.of(UserAccount.Role.STUDENT), created.roles);
        assertEquals(created.id, auth.login("openid-approve-new").user().id());
        assertThrows(ApiException.class, () -> auth.authenticate(oldLogin.token()));
        assertEquals(admin.id, appeals.findById(appealId).orElseThrow().reviewer.getId());
    }

    @Test
    void organizerOwnershipMustBeResolvedBeforeApproval() throws Exception {
        UserAccount source = users.saveAndFlush(UserAccount.selfRegisteredStudent("原组织者", "20268888880007"));
        source.email = "approve-organizer@test.local";
        source.passwordHash = passwords.encode("student123");
        source.roles.add(UserAccount.Role.ORGANIZER);
        users.saveAndFlush(source);
        users.saveAndFlush(new UserAccount("组织者审核员", "approve-organizer-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        MiniappAuthService.LoginResult oldLogin = auth.bind("openid-approve-organizer", source.email, "student123");
        long appealId = submitAppeal("organizer-code", "openid-approve-organizer",
            "WRONG_STUDENT_NUMBER", "20268888880008");
        MockHttpSession session = adminSession("approve-organizer-admin@test.local");
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifiedName\":\"新学生\",\"reason\":\"已核对\","
                    + "\"replaceTargetBinding\":false,\"decisions\":[]}"))
            .andExpect(status().isConflict());
        assertEquals(BindingAppeal.Status.PENDING, appeals.findById(appealId).orElseThrow().status);
        assertEquals(source.id, auth.authenticate(oldLogin.token()).getId());
        assertTrue(users.findByStudentNumber("20268888880008").isEmpty());
    }

    @Test
    void twoHistoricalRegistrationsForOneActivityCannotBothTransfer() throws Exception {
        UserAccount first = users.saveAndFlush(UserAccount.selfRegisteredStudent("历史账号甲", "20268888880009"));
        first.email = "approve-dupe-first@test.local";
        first.passwordHash = passwords.encode("student123");
        users.saveAndFlush(first);
        UserAccount second = users.saveAndFlush(UserAccount.selfRegisteredStudent("历史账号乙", "20268888880010"));
        users.saveAndFlush(UserAccount.selfRegisteredStudent("目标学生", "20268888880011"));
        UserAccount admin = users.saveAndFlush(new UserAccount("重复记录管理员", "approve-dupe-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        auth.bind("openid-dupe-history", first.email, "student123");
        WechatStudentBinding firstBinding = bindings.findByAppIdAndOpenIdAndUnboundAtIsNull(
            "test-miniapp", "openid-dupe-history").orElseThrow();
        WechatIdentity identity = identities.findByAppIdAndOpenId("test-miniapp", "openid-dupe-history")
            .orElseThrow();
        firstBinding.unbind(admin, "历史核对", Instant.now());
        bindings.saveAndFlush(firstBinding);
        WechatStudentBinding secondBinding = bindings.saveAndFlush(new WechatStudentBinding(identity, second));
        Activity activity = activity(admin, "重复历史报名活动");
        Registration firstRow = new Registration(activity, first);
        firstRow.identityId = identity.getId();
        firstRow.bindingId = firstBinding.getId();
        registrations.saveAndFlush(firstRow);
        Registration secondRow = new Registration(activity, second);
        secondRow.identityId = identity.getId();
        secondRow.bindingId = secondBinding.getId();
        registrations.saveAndFlush(secondRow);
        long appealId = submitAppeal("dupe-code", "openid-dupe-history", "WRONG_STUDENT_NUMBER",
            "20268888880011");
        MockHttpSession session = adminSession("approve-dupe-admin@test.local");
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifiedName\":\"目标学生\",\"reason\":\"证件已核对\","
                    + "\"replaceTargetBinding\":false,\"decisions\":["
                    + "{\"registrationId\":" + firstRow.id + ",\"decision\":\"TRANSFER\",\"reason\":\"本人记录\"},"
                    + "{\"registrationId\":" + secondRow.id + ",\"decision\":\"TRANSFER\",\"reason\":\"本人记录\"}]}"))
            .andExpect(status().isConflict());
        assertEquals(first.id, registrations.findById(firstRow.id).orElseThrow().student.getId());
        assertEquals(second.id, registrations.findById(secondRow.id).orElseThrow().student.getId());
        assertEquals(second.id, auth.login("openid-dupe-history").user().id());
        assertEquals(BindingAppeal.Status.PENDING, appeals.findById(appealId).orElseThrow().status);
        assertEquals(0, attributionReviews.findByAppealIdOrderByRegistrationId(appealId).size());
    }

    @Test
    void disputedAppealKeepsEvidenceAndBindingUntilExplicitApproval() throws Exception {
        UserAccount source = users.saveAndFlush(UserAccount.selfRegisteredStudent("待核原账号", "20268888880012"));
        source.email = "dispute-source@test.local";
        source.passwordHash = passwords.encode("student123");
        users.saveAndFlush(source);
        UserAccount target = users.saveAndFlush(UserAccount.selfRegisteredStudent("核对后的学生", "20268888880013"));
        UserAccount admin = users.saveAndFlush(new UserAccount("争议审核员", "dispute-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        MiniappAuthService.LoginResult oldLogin = auth.bind("openid-disputed", source.email, "student123");
        Activity activity = activity(admin, "争议活动");
        Registration row = registrations.saveAndFlush(new Registration(activity, source));
        long appealId = submitAppeal("dispute-code", "openid-disputed", "WRONG_STUDENT_NUMBER",
            "20268888880013");
        MockHttpSession session = adminSession("dispute-admin@test.local");
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/dispute")
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"同活动已有历史记录，需继续核对\"}"))
            .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/dispute")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"同活动已有历史记录，需继续核对\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPUTED"));
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/dispute")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"重复标记\"}"))
            .andExpect(status().isConflict());
        mvc().perform(get("/api/admin/binding-appeals").session(session).param("queue", "DISPUTED"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(appealId));
        mvc().perform(get("/api/admin/binding-appeals/pending").session(session))
            .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem((int) appealId)));
        mvc().perform(get("/api/admin/binding-appeals").session(session).param("queue", "REVIEWED")
                .param("studentNumber", "20268888880013"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"dispute-code\"}"))
            .andExpect(status().isConflict());
        BindingAppeal disputed = appeals.findById(appealId).orElseThrow();
        disputed.reviewedAt = Instant.now().minusSeconds(31L * 24 * 3600);
        appeals.saveAndFlush(disputed);
        cleanup.removeExpiredEvidence();
        assertEquals(null, appeals.findById(appealId).orElseThrow().evidenceDeletedAt);
        assertEquals(source.id, auth.authenticate(oldLogin.token()).getId());
        assertEquals(source.id, registrations.findById(row.id).orElseThrow().student.getId());
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/approve")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"verifiedName\":\"核对后的学生\",\"reason\":\"记录属于申请人\"," 
                    + "\"replaceTargetBinding\":false,\"decisions\":[{\"registrationId\":" + row.id
                    + ",\"decision\":\"TRANSFER\",\"reason\":\"原微信历史记录\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertEquals(target.id, auth.login("openid-disputed").user().id());
        assertEquals(target.id, registrations.findById(row.id).orElseThrow().student.getId());
        assertTrue(appeals.findById(appealId).orElseThrow().reviewNote.contains("同活动已有历史记录"));
        assertThrows(ApiException.class, () -> auth.authenticate(oldLogin.token()));
    }

    @Test
    void disputedAppealCanBeRejectedWithoutMovingRecords() throws Exception {
        UserAccount admin = users.saveAndFlush(new UserAccount("驳回争议管理员", "dispute-reject-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        long appealId = submitAppeal("dispute-reject-code", "openid-dispute-reject",
            "STUDENT_NUMBER_OCCUPIED", "20268888880014");
        MockHttpSession session = adminSession("dispute-reject-admin@test.local");
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/dispute")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"证明材料仍需进一步核实\"}"))
            .andExpect(status().isOk());
        mvc().perform(post("/api/admin/binding-appeals/" + appealId + "/reject")
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"证件无法核对\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        BindingAppeal rejected = appeals.findById(appealId).orElseThrow();
        assertTrue(rejected.reviewNote.contains("证明材料仍需进一步核实"));
        assertEquals(admin.id, rejected.reviewer.getId());
        mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"dispute-reject-code\"}"))
            .andExpect(status().isOk());
    }

    private long submitAppeal(String code, String openId, String type, String number) throws Exception {
        when(wechat.exchange(code)).thenReturn(openId);
        String body = mvc().perform(post("/api/miniapp/auth/appeals/challenge")
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = new ObjectMapper().readTree(body).path("token").asText();
        mvc().perform(multipart("/api/miniapp/auth/appeals/evidence")
                .file(new MockMultipartFile("file", "card.png", "image/png", photo()))
                .header("X-Appeal-Challenge", token)).andExpect(status().isOk());
        String submitted = mvc().perform(post("/api/miniapp/auth/appeals")
                .header("X-Appeal-Challenge", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"" + type + "\",\"proposedStudentNumber\":\"" + number
                    + "\",\"reason\":\"请核对学生证\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(submitted).path("id").asLong();
    }

    private MockHttpSession adminSession(String email) throws Exception {
        return (MockHttpSession) mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"adminpass123\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private Activity activity(UserAccount organizer, String title) {
        Activity activity = new Activity();
        Instant now = Instant.now();
        activity.title = title;
        activity.description = title;
        activity.location = "报告厅";
        activity.organizer = organizer;
        activity.startsAt = now.plusSeconds(3600);
        activity.endsAt = now.plusSeconds(7200);
        activity.registrationOpensAt = now.minusSeconds(600);
        activity.registrationClosesAt = now.plusSeconds(1800);
        activity.checkinOpensAt = now.plusSeconds(3500);
        activity.checkinClosesAt = now.plusSeconds(7000);
        activity.capacity = 20;
        return activities.saveAndFlush(activity);
    }

    private static byte[] photo() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}

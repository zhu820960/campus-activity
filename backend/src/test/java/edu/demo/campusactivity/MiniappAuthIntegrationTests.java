package edu.demo.campusactivity;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:miniapp_auth;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp",
    "app.binding-management.enabled=true"
})
class MiniappAuthIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired MiniappAuthService auth;
    @Autowired MiniappAccessTokenRepository tokens;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired WechatStudentBindingRepository bindings;
    @Autowired ActivityService activities;
    @Autowired ActivityRepository activityRepository;
    @Autowired RegistrationRepository registrations;
    @Autowired CheckinRepository checkinRepository;
    @Autowired CheckinService checkins;
    @MockitoBean WechatCodeExchange wechat;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void firstBindReloginAndLogoutUseHashedRevocableToken() throws Exception {
        UserAccount student = student("bind");
        when(wechat.exchange("first-code")).thenReturn("openid-bind");
        when(wechat.exchange("later-code")).thenReturn("openid-bind");

        String first = mvc().perform(post("/api/miniapp/auth/bind")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"first-code\",\"email\":\"bind@test.local\",\"password\":\"student123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(student.id))
            .andReturn().getResponse().getContentAsString();
        String raw = new tools.jackson.databind.ObjectMapper().readTree(first).path("token").asText();
        assertEquals(43, raw.length());
        MiniappAccessToken issued = tokens.findByTokenHash(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII))))
            .orElseThrow();
        assertEquals(64, issued.tokenHash.length());
        assertNotEquals(raw, issued.tokenHash);
        assertTrue(issued.expiresAt.isAfter(Instant.now().plusSeconds(6 * 24 * 3600)));
        assertEquals(null, issued.revokedAt);
        assertEquals(student.id, auth.authenticate(raw).getId());

        mvc().perform(get("/api/miniapp/auth/me").header("Authorization", "Bearer " + raw))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(student.id));
        mvc().perform(post("/api/miniapp/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"later-code\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(student.id));
        mvc().perform(post("/api/miniapp/auth/logout").header("Authorization", "Bearer " + raw))
            .andExpect(status().isNoContent());
        mvc().perform(get("/api/miniapp/auth/me").header("Authorization", "Bearer " + raw))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void unboundTokenFailsImmediatelyAndSelfRebindRequiresAppeal() throws Exception {
        UserAccount student = student("rebind");
        String oldToken = auth.bind("openid-rebind", student.email, "student123").token();
        WechatStudentBinding oldBinding = bindings.findByAppIdAndOpenIdAndUnboundAtIsNull(
            "test-miniapp", "openid-rebind").orElseThrow();
        oldBinding.unboundAt = Instant.now();
        bindings.saveAndFlush(oldBinding);
        assertNotNull(bindings.findById(oldBinding.id).orElseThrow().unboundAt);
        assertNotNull(jdbc.queryForObject("select unbound_at from wechat_student_bindings where id = ?",
            java.time.LocalDateTime.class, oldBinding.id));
        assertThrows(ApiException.class, () -> auth.authenticate(oldToken));

        mvc().perform(get("/api/miniapp/auth/me").header("Authorization", "Bearer " + oldToken))
            .andExpect(status().isUnauthorized());
        ApiException blocked = assertThrows(ApiException.class,
            () -> auth.bind("openid-rebind", student.email, "student123"));
        assertEquals(409, blocked.status.value());
        assertEquals(1, bindings.findAll().stream()
            .filter(binding -> binding.openId.equals("openid-rebind")).count());
        mvc().perform(get("/api/miniapp/auth/me").header("Authorization", "Bearer " + oldToken))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminUnbindRevokesMiniappTokenAndExistingOrganizerWebSession() throws Exception {
        UserAccount student = student("admin-unbind-student");
        student.studentNumber = "00000000000097";
        student.roles.add(UserAccount.Role.ORGANIZER);
        users.saveAndFlush(student);
        UserAccount admin = users.saveAndFlush(new UserAccount("解绑管理员", "unbind-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        String raw = auth.bind("openid-admin-unbind", student.email, "student123").token();
        long bindingId = bindings.findByAppIdAndStudentIdAndUnboundAtIsNull("test-miniapp", student.id)
            .orElseThrow().id;
        HttpSession organizerSession = mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"00000000000097\",\"password\":\"student123\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        HttpSession adminSession = mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"unbind-admin@test.local\",\"password\":\"adminpass123\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);

        mvc().perform(patch("/api/admin/users/" + student.id + "/wechat-binding")
                .session((org.springframework.mock.web.MockHttpSession) adminSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"学生本人申请纠正误绑\"}"))
            .andExpect(status().isOk());
        assertNotNull(bindings.findById(bindingId).orElseThrow().unboundAt);
        assertEquals(admin.id, bindings.findById(bindingId).orElseThrow().unboundBy.getId());
        assertEquals("学生本人申请纠正误绑", bindings.findById(bindingId).orElseThrow().unboundReason);
        assertEquals(null, users.findById(student.id).orElseThrow().passwordHash);
        assertEquals(0, jdbc.queryForObject("select count(*) from miniapp_access_tokens "
            + "where binding_id = ? and revoked_at is null", Integer.class, bindingId));
        assertEquals(1, jdbc.queryForObject("select count(*) from user_security_audits "
            + "where target_id = ? and action = 'WECHAT_UNBOUND'", Integer.class, student.id));
        mvc().perform(get("/api/miniapp/auth/me").header("Authorization", "Bearer " + raw))
            .andExpect(status().isUnauthorized());
        mvc().perform(get("/api/auth/me")
                .session((org.springframework.mock.web.MockHttpSession) organizerSession))
            .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"00000000000097\",\"password\":\"student123\"}"))
            .andExpect(status().isUnauthorized());
        UserAccount reactivated = users.findById(student.id).orElseThrow();
        reactivated.passwordHash = passwords.encode("student123");
        users.saveAndFlush(reactivated);
        mvc().perform(get("/api/auth/me")
                .session((org.springframework.mock.web.MockHttpSession) organizerSession))
            .andExpect(status().isUnauthorized());
        HttpSession freshSession = mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"00000000000097\",\"password\":\"student123\"}"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        mvc().perform(get("/api/auth/me")
                .session((org.springframework.mock.web.MockHttpSession) freshSession))
            .andExpect(status().isOk());
    }

    @Test
    void bindingRejectsWrongPasswordNonStudentAndOneToOneConflicts() throws Exception {
        student("conflict");
        student("conflict-other");
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "nonstudent@test.local",
            passwords.encode("student123"), UserAccount.Role.ADMIN));
        when(wechat.exchange("bind-code")).thenReturn("openid-conflict");
        when(wechat.exchange("other-code")).thenReturn("openid-other");
        when(wechat.exchange("admin-code")).thenReturn("openid-admin");

        mvc().perform(post("/api/miniapp/auth/bind").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"bind-code\",\"email\":\"conflict@test.local\",\"password\":\"wrongpass\"}"))
            .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/miniapp/auth/bind").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"admin-code\",\"email\":\"nonstudent@test.local\",\"password\":\"student123\"}"))
            .andExpect(status().isForbidden());
        assertTrue(admin.roles.contains(UserAccount.Role.ADMIN));

        auth.bind("openid-conflict", "conflict@test.local", "student123");
        assertThrows(ApiException.class, () -> auth.bind("openid-conflict", "conflict@test.local", "student123"));
        assertThrows(ApiException.class, () -> auth.bind("openid-conflict", "conflict-other@test.local", "student123"));
        assertThrows(ApiException.class, () -> auth.bind("openid-other", "conflict@test.local", "student123"));
        assertEquals(1, bindings.findAll().stream().filter(b -> b.getStudent().getId().equals(
            users.findByEmailIgnoreCase("conflict@test.local").orElseThrow().id)).count());
    }

    @Test
    void expiredAndRevokedTokensCannotAccessStudentOrAdminApis() throws Exception {
        student("expiry");
        String raw = auth.bind("openid-expiry", "expiry@test.local", "student123").token();
        mvc().perform(get("/api/miniapp/my/registrations")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/admin/activities").header("Authorization", "Bearer " + raw))
            .andExpect(status().isUnauthorized());
        mvc().perform(get("/api/miniapp/my/registrations").header("Authorization", "Bearer " + raw))
            .andExpect(status().isOk());
        MiniappAccessToken saved = tokens.findByTokenHash(HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII))))
            .orElseThrow();
        saved.expiresAt = Instant.now().minusSeconds(1);
        tokens.saveAndFlush(saved);
        mvc().perform(get("/api/miniapp/my/registrations").header("Authorization", "Bearer " + raw))
            .andExpect(status().isUnauthorized());
        assertFalse(tokens.findAll().isEmpty());
    }

    @Test
    void currentStudentRoleIsRecheckedForEveryRequest() throws Exception {
        UserAccount student = student("role-change");
        String raw = auth.bind("openid-role-change", student.email, "student123").token();
        mvc().perform(get("/api/miniapp/auth/me").header("Authorization", "Bearer " + raw))
            .andExpect(status().isOk());
        student.roles.clear();
        users.saveAndFlush(student);
        mvc().perform(get("/api/miniapp/auth/me").header("Authorization", "Bearer " + raw))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void competingWechatIdentitiesCannotBindSameStudent() throws Exception {
        student("race");
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> bind = () -> {
                start.await(5, TimeUnit.SECONDS);
                String openId = "openid-race-" + Thread.currentThread().getId();
                try {
                    auth.bind(openId, "race@test.local", "student123");
                    return true;
                } catch (ApiException error) {
                    assertEquals(409, error.status.value());
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(bind);
            Future<Boolean> second = pool.submit(bind);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, bindings.findAll().stream().filter(b -> b.getStudent().getId().equals(
            users.findByEmailIgnoreCase("race@test.local").orElseThrow().id)).count());
    }

    @Test
    void webAdminStillNeedsCsrfAndUsesItsOwnSession() throws Exception {
        users.saveAndFlush(new UserAccount("Web管理员", "web-admin@test.local",
            passwords.encode("adminpass123"), UserAccount.Role.ADMIN));
        String body = "{\"email\":\"web-admin@test.local\",\"password\":\"adminpass123\"}";
        mvc().perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        HttpSession session = mvc().perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("ADMIN"))
            .andReturn().getRequest().getSession(false);
        mvc().perform(get("/api/auth/me").session((org.springframework.mock.web.MockHttpSession) session))
            .andExpect(status().isOk());
        mvc().perform(get("/api/admin/activities").session((org.springframework.mock.web.MockHttpSession) session))
            .andExpect(status().isOk());
        mvc().perform(get("/api/miniapp/auth/me").session((org.springframework.mock.web.MockHttpSession) session))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void studentBusinessUsesBearerIdentityAndExistingRegistrationAndQrRules() throws Exception {
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "flow-admin@test.local",
            passwords.encode("student123"), UserAccount.Role.ADMIN));
        UserAccount one = student("flow-one");
        UserAccount two = student("flow-two");
        Instant now = Instant.now();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin.id, null, List.of()));
        ActivityView draft = activities.createForAdmin(new ActivityService.ActivityInput(
            "小程序活动", "活动正文", "摘要", null, "报告厅", 108.93, 34.34,
            now.plusSeconds(3600), now.plusSeconds(7200), now.minusSeconds(600),
            now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 1));
        activities.publishOwnForAdmin(draft.id());
        String qr = (String) checkins.issueAdminToken(draft.id()).get("token");
        SecurityContextHolder.clearContext();

        String first = auth.bind("openid-one", one.email, "student123").token();
        String second = auth.bind("openid-two", two.email, "student123").token();
        MiniappAttribution secondAttribution = auth.authenticateWithAttribution(second).attribution();
        mvc().perform(post("/api/miniapp/activities/" + draft.id() + "/registrations")
                .header("Authorization", "Bearer " + first)).andExpect(status().isOk());
        mvc().perform(post("/api/miniapp/activities/" + draft.id() + "/registrations")
                .header("Authorization", "Bearer " + first)).andExpect(status().isConflict());
        mvc().perform(post("/api/miniapp/activities/" + draft.id() + "/registrations")
                .header("Authorization", "Bearer " + second)).andExpect(status().isConflict());
        mvc().perform(get("/api/miniapp/my/registrations").header("Authorization", "Bearer " + second))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0]").doesNotExist());
        mvc().perform(post("/api/miniapp/activities/" + draft.id() + "/checkins")
                .header("Authorization", "Bearer " + second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + qr + "\"}"))
            .andExpect(status().isConflict());
        mvc().perform(delete("/api/miniapp/activities/" + draft.id() + "/registrations")
                .header("Authorization", "Bearer " + first)).andExpect(status().isNoContent());
        mvc().perform(post("/api/miniapp/activities/" + draft.id() + "/registrations")
                .header("Authorization", "Bearer " + second)).andExpect(status().isOk());
        Registration secondRegistration = registrations.findByActivityIdAndStudentId(draft.id(), two.id)
            .orElseThrow();
        assertEquals(secondAttribution.identityId(), secondRegistration.identityId);
        assertEquals(secondAttribution.bindingId(), secondRegistration.bindingId);
        mvc().perform(post("/api/miniapp/activities/" + draft.id() + "/checkins")
                .header("Authorization", "Bearer " + second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"v2.invalid\"}"))
            .andExpect(status().isBadRequest());
        mvc().perform(post("/api/miniapp/activities/" + draft.id() + "/checkins")
                .header("Authorization", "Bearer " + second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + qr + "\"}"))
            .andExpect(status().isOk());
        Checkin secondCheckin = checkinRepository.findByActivityIdAndStudentId(draft.id(), two.id)
            .orElseThrow();
        assertEquals(secondAttribution.identityId(), secondCheckin.identityId);
        assertEquals(secondAttribution.bindingId(), secondCheckin.bindingId);
        mvc().perform(post("/api/miniapp/activities/" + draft.id() + "/checkins")
                .header("Authorization", "Bearer " + second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + qr + "\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void legacyParticipationIsNotClaimedByCurrentWechatBinding() throws Exception {
        UserAccount student = student("legacy-owner");
        Activity activity = new Activity();
        Instant now = Instant.now();
        activity.title = "历史活动";
        activity.description = "历史报名归属未核实";
        activity.location = "报告厅";
        activity.startsAt = now.plusSeconds(3600);
        activity.endsAt = now.plusSeconds(7200);
        activity.registrationOpensAt = now.minusSeconds(600);
        activity.registrationClosesAt = now.plusSeconds(1800);
        activity.checkinOpensAt = now.plusSeconds(3500);
        activity.checkinClosesAt = now.plusSeconds(7000);
        activity.capacity = 5;
        activity.registeredCount = 1;
        activity.organizer = student;
        activity.status = Activity.Status.PUBLISHED;
        activity = activityRepository.saveAndFlush(activity);
        Registration legacy = registrations.saveAndFlush(new Registration(activity, student));
        String raw = auth.bind("openid-legacy-owner", student.email, "student123").token();

        mvc().perform(get("/api/miniapp/my/registrations").header("Authorization", "Bearer " + raw))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0]").doesNotExist());
        mvc().perform(delete("/api/miniapp/activities/" + activity.id + "/registrations")
                .header("Authorization", "Bearer " + raw)).andExpect(status().isNotFound());
        mvc().perform(post("/api/miniapp/activities/" + activity.id + "/registrations")
                .header("Authorization", "Bearer " + raw)).andExpect(status().isConflict());
        assertEquals(null, registrations.findById(legacy.id).orElseThrow().identityId);
    }

    private UserAccount student(String name) {
        return users.saveAndFlush(new UserAccount(name, name + "@test.local",
            passwords.encode("student123"), UserAccount.Role.STUDENT));
    }
}

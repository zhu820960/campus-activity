package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:student_number_binding;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp"
})
class StudentNumberBindingTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired WechatStudentBindingRepository bindings;
    @Autowired MiniappAuthService auth;
    @Autowired ActivityService activities;
    @Autowired CurrentUser current;
    @Autowired AdminController administration;
    @Autowired UserSecurityAuditRepository securityAudits;
    @Autowired PasswordEncoder passwords;
    @MockitoBean WechatCodeExchange wechat;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void firstLoginThenFreshCodeBindingOpensProtectedHall() throws Exception {
        when(wechat.exchange("first-code")).thenReturn("openid-new-student");
        when(wechat.exchange("bind-code")).thenReturn("openid-new-student");
        when(wechat.exchange("next-code")).thenReturn("openid-new-student");

        mvc().perform(post("/api/miniapp/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"first-code\"}"))
            .andExpect(status().isConflict());
        assertEquals(0, users.findAll().stream().filter(u -> "00000000000001".equals(u.studentNumber)).count());

        String response = mvc().perform(post("/api/miniapp/auth/bind-student")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"bind-code\",\"studentNumber\":\"00000000000001\",\"name\":\" 测试学生 \"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.name").value("测试学生"))
            .andExpect(jsonPath("$.user.studentNumber").value("00000000000001"))
            .andExpect(jsonPath("$.user.roles[0]").value("STUDENT"))
            .andReturn().getResponse().getContentAsString();
        String bearer = new ObjectMapper().readTree(response).path("token").asText();
        UserAccount student = users.findByStudentNumber("00000000000001").orElseThrow();
        assertNull(student.email);
        assertNull(student.passwordHash);
        assertEquals(1, bindings.findAll().stream()
            .filter(b -> b.getStudent().getId().equals(student.id)).count());

        mvc().perform(get("/api/activities")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/activities").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isUnauthorized());
        mvc().perform(get("/api/miniapp/activities").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk());
        mvc().perform(get("/api/miniapp/announcements").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk());
        mvc().perform(get("/api/miniapp/auth/me").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.studentNumber").value("00000000000001"));
        mvc().perform(post("/api/miniapp/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"next-code\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(student.id));
        verify(wechat).exchange("first-code");
        verify(wechat).exchange("bind-code");
    }

    @Test
    void rejectsInvalidNumberNameAndCompetingBindings() {
        assertEquals(400, assertThrows(ApiException.class,
            () -> auth.bindStudent("openid-invalid", "123", "姓名")).status.value());
        assertEquals(400, assertThrows(ApiException.class,
            () -> auth.bindStudent("openid-invalid", "22222222222", "姓名")).status.value());
        assertEquals(400, assertThrows(ApiException.class,
            () -> auth.bindStudent("openid-invalid", "1234567890a", "姓名")).status.value());
        assertEquals(400, assertThrows(ApiException.class,
            () -> auth.bindStudent("openid-invalid", "1234567890123", "姓名")).status.value());
        assertEquals(400, assertThrows(ApiException.class,
            () -> auth.bindStudent("openid-invalid", "123456789012345", "姓名")).status.value());
        assertEquals(400, assertThrows(ApiException.class,
            () -> auth.bindStudent("openid-invalid", "12345678901234", "   ")).status.value());

        auth.bindStudent("openid-one", "00000000000002", "学生一");
        assertEquals(409, assertThrows(ApiException.class,
            () -> auth.bindStudent("openid-two", "00000000000002", "学生二")).status.value());
        assertEquals(409, assertThrows(ApiException.class,
            () -> auth.bindStudent("openid-one", "00000000000003", "学生一")).status.value());
        assertEquals(1, bindings.findAll().stream().filter(b -> b.getStudent().getId().equals(
            users.findByStudentNumber("00000000000002").orElseThrow().id)).count());
        assertFalse(users.findByStudentNumber("00000000000003").isPresent());
    }

    @Test
    void concurrentWechatAccountsCannotClaimSameNumber() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> claim = () -> {
                start.await(5, TimeUnit.SECONDS);
                try {
                    auth.bindStudent("openid-" + Thread.currentThread().getId(), "00000000000004", "并发学生");
                    return true;
                } catch (ApiException error) {
                    assertEquals(409, error.status.value());
                    return false;
                }
            };
            Future<Boolean> first = pool.submit(claim);
            Future<Boolean> second = pool.submit(claim);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, users.findAll().stream().filter(u -> "00000000000004".equals(u.studentNumber)).count());
    }

    @Test
    void protectedContentIncludesPublishedOnlyAndPreservesWebAdmin() throws Exception {
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "student-number-admin@test.local",
            passwords.encode("admin-test-password"), UserAccount.Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin.id, null, List.of()));
        Instant now = Instant.now();
        ActivityService.ActivityInput input = new ActivityService.ActivityInput("校内测试活动", "正文", "摘要",
            null, "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(600), now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 20);
        ActivityView draft = activities.createForAdmin(input);
        ActivityView published = activities.createForAdmin(input);
        activities.publishOwnForAdmin(published.id());
        SecurityContextHolder.clearContext();

        String bearer = auth.bindStudent("openid-reader", "00000000000005", "阅读学生").token();
        mvc().perform(get("/api/miniapp/activities").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + published.id() + ")]").exists())
            .andExpect(jsonPath("$[?(@.id == " + draft.id() + ")]").doesNotExist());
        mvc().perform(get("/api/miniapp/activities/" + published.id())
                .header("Authorization", "Bearer " + bearer)).andExpect(status().isOk());
        mvc().perform(get("/api/miniapp/activities/" + draft.id())
                .header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden());
        mvc().perform(get("/api/miniapp/activities/" + published.id())).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/announcements")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/admin/activities").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isUnauthorized());

        mvc().perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"student-number-admin@test.local\",\"password\":\"admin-test-password\"}"))
            .andExpect(status().isOk());
        assertTrue(users.findById(admin.id).orElseThrow().roles.contains(UserAccount.Role.ADMIN));
    }

    @Test
    void removedOrganizerRoleInvalidatesExistingWebAuthority() {
        UserAccount student = users.saveAndFlush(new UserAccount("组织者", "role-revoked@test.local",
            passwords.encode("organizer-password"), UserAccount.Role.STUDENT));
        student.roles.add(UserAccount.Role.ORGANIZER);
        users.saveAndFlush(student);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            student.id, null, List.of(new SimpleGrantedAuthority("ROLE_ORGANIZER"))));
        assertEquals(student.id, current.require(UserAccount.Role.ORGANIZER).id);
        UserAccount persisted = users.findById(student.id).orElseThrow();
        persisted.roles.remove(UserAccount.Role.ORGANIZER);
        users.saveAndFlush(persisted);
        assertFalse(users.findById(student.id).orElseThrow().roles.contains(UserAccount.Role.ORGANIZER));
        assertEquals(401, assertThrows(ApiException.class,
            () -> current.require(UserAccount.Role.ORGANIZER)).status.value());
    }

    @Test
    void adminRoleChangesAreAuditedAndCannotPromoteNonStudent() {
        UserAccount admin = users.saveAndFlush(new UserAccount("审计管理员", "audit-admin@test.local",
            passwords.encode("audit-password"), UserAccount.Role.ADMIN));
        auth.bindStudent("audit-student-openid", "00000000000015", "审计学生");
        UserAccount student = users.findByStudentNumber("00000000000015").orElseThrow();
        UserAccount outsider = users.saveAndFlush(new UserAccount("非学生", "outsider@test.local",
            "unused", UserAccount.Role.ORGANIZER));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin.id, null, List.of()));
        long before = securityAudits.count();
        administration.organizer(student.id, new AdminController.RoleRequest(true));
        administration.organizer(student.id, new AdminController.RoleRequest(true));
        administration.organizer(student.id, new AdminController.RoleRequest(false));
        assertEquals(before + 2, securityAudits.count());
        assertEquals(UserSecurityAudit.Action.ORGANIZER_REVOKED,
            administration.userAudits().get(0).action());
        assertEquals(400, assertThrows(ApiException.class,
            () -> administration.organizer(outsider.id, new AdminController.RoleRequest(true))).status.value());
    }

    @Test
    void adminUserListShowsBindingAndCredentialStateWithoutOpenId() {
        auth.bindStudent("admin-list-openid", "00000000000014", "列表学生");
        UserAccount admin = users.saveAndFlush(new UserAccount("列表管理员", "list-admin@test.local",
            passwords.encode("list-password123"), UserAccount.Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin.id, null, List.of()));
        AdminController.AdminUserView student = administration.users().stream()
            .filter(row -> "00000000000014".equals(row.studentNumber())).findFirst().orElseThrow();
        assertTrue(student.wechatBound());
        assertFalse(student.webCredentialsActive());
        assertEquals(0, student.activityCount());
    }

    @Test
    void studentOnlyLegacyWebPasswordCannotBypassContentProtection() throws Exception {
        users.saveAndFlush(new UserAccount("旧学生", "legacy-student@test.local",
            passwords.encode("student-test-password"), UserAccount.Role.STUDENT));
        mvc().perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"legacy-student@test.local\",\"password\":\"student-test-password\"}"))
            .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"匿名\",\"email\":\"new@test.local\",\"password\":\"password123\"}"))
            .andExpect(status().isUnauthorized());
    }
}

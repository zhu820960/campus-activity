package edu.demo.campusactivity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:organizer_activation;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp"
})
class OrganizerWebActivationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired MiniappAuthService auth;
    @MockitoBean WechatCodeExchange wechat;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void boundOrganizerCanActivateOnceAndLoginToWeb() throws Exception {
        String bearer = auth.bindStudent("org-openid-1", "00000000000011", "组织者甲").token();
        UserAccount organizer = users.findByStudentNumber("00000000000011").orElseThrow();
        assertNull(organizer.passwordHash);
        organizer.roles.add(UserAccount.Role.ORGANIZER);
        users.saveAndFlush(organizer);
        when(wechat.exchange("org-fresh-code")).thenReturn("org-openid-1");
        String request = "{\"code\":\"org-fresh-code\",\"password\":\"goodPassword123\"}";

        mvc().perform(post("/api/miniapp/auth/web-credentials")
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isUnauthorized());
        mvc().perform(get("/api/admin/activities").header("Authorization", "Bearer " + bearer))
            .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/miniapp/auth/web-credentials")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk()).andExpect(jsonPath("$.studentNumber").value("00000000000011"))
            .andExpect(jsonPath("$.webCredentialsActive").value(true));
        assertNotNull(users.findByStudentNumber("00000000000011").orElseThrow().passwordHash);
        mvc().perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"00000000000011\",\"password\":\"goodPassword123\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.roles[?(@ == 'ORGANIZER')]").exists());
        mvc().perform(post("/api/miniapp/auth/web-credentials")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isConflict());
    }

    @Test
    void refusesWrongWechatStudentAndOrdinaryStudent() throws Exception {
        String bearer = auth.bindStudent("org-openid-2", "00000000000012", "组织者乙").token();
        UserAccount organizer = users.findByStudentNumber("00000000000012").orElseThrow();
        organizer.roles.add(UserAccount.Role.ORGANIZER);
        users.saveAndFlush(organizer);
        when(wechat.exchange("wrong-code")).thenReturn("unrelated-openid");
        String request = "{\"code\":\"wrong-code\",\"password\":\"goodPassword123\"}";
        mvc().perform(post("/api/miniapp/auth/web-credentials")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isForbidden());
        assertNull(users.findByStudentNumber("00000000000012").orElseThrow().passwordHash);

        String studentBearer = auth.bindStudent("student-only-openid", "00000000000013", "普通学生").token();
        when(wechat.exchange("student-code")).thenReturn("student-only-openid");
        mvc().perform(post("/api/miniapp/auth/web-credentials")
                .header("Authorization", "Bearer " + studentBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"student-code\",\"password\":\"goodPassword123\"}"))
            .andExpect(status().isForbidden());
    }
}

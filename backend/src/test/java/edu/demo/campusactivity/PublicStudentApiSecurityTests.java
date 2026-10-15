package edu.demo.campusactivity;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:public_api;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PublicStudentApiSecurityTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired ActivityService activities;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    void anonymousCannotReadCampusContentOrPersonalData() throws Exception {
        mvc().perform(get("/api/activities")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/announcements")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/miniapp/activities")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/miniapp/announcements")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/my/registrations")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/admin/activities")).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousCannotRegisterEvenThoughActivitiesArePublic() throws Exception {
        mvc().perform(post("/api/activities/1/registrations")).andExpect(status().isForbidden());
    }

    @Test
    void authorizedWebManagerSeesOnlyPublishedActivitiesThroughSharedReadApi() throws Exception {
        UserAccount admin = users.saveAndFlush(new UserAccount("测试发布者", "public-api-admin@test.local", "unused",
            UserAccount.Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin.getId(), null, List.of()));
        Instant now = Instant.now();
        ActivityService.ActivityInput input = new ActivityService.ActivityInput("公开活动", "正文", "导语",
            null, "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(600), now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 20);
        ActivityView draft = activities.createForAdmin(input);
        ActivityView published = activities.createForAdmin(input);
        activities.publishOwnForAdmin(published.id());
        SecurityContextHolder.clearContext();

        var manager = SecurityMockMvcRequestPostProcessors.authentication(
            new UsernamePasswordAuthenticationToken(admin.id, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        mvc().perform(get("/api/activities").with(manager)).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + published.id() + ")]").exists())
            .andExpect(jsonPath("$[?(@.id == " + draft.id() + ")]").doesNotExist());
        mvc().perform(get("/api/activities/" + published.id()).with(manager)).andExpect(status().isOk());
        mvc().perform(get("/api/activities/" + draft.id())).andExpect(status().isUnauthorized());
    }
}

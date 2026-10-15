package edu.demo.campusactivity;

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
    "spring.datasource.url=jdbc:h2:mem:ai_draft;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.deepseek.api-key="
})
class AiDraftControllerTests {
    @Autowired UserRepository users;
    @Autowired AiDraftController drafts;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void newActivityCanRequestDraftButMissingKeyDoesNotGenerateFakeText() {
        UserAccount admin = users.saveAndFlush(new UserAccount("管理员", "ai-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        authenticate(admin);
        AiDraftController.DraftRequest request = new AiDraftController.DraftRequest(null,
            AiDraftController.Kind.INTRO, "写得简洁", "社团开放日", "报告厅", null, null, "现场交流",
            null, null, null);
        assertEquals(503, assertThrows(ApiException.class, () -> drafts.draft(request)).status.value());
        assertEquals(400, assertThrows(ApiException.class, () -> drafts.draft(new AiDraftController.DraftRequest(
            null, AiDraftController.Kind.INTRO, null, "", "报告厅", null, null, null,
            null, null, null))).status.value());
    }

    @Test
    void studentCannotCallDraftAssistant() {
        UserAccount student = users.saveAndFlush(new UserAccount("学生", "ai-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        authenticate(student);
        assertEquals(403, assertThrows(ApiException.class, () -> drafts.draft(new AiDraftController.DraftRequest(
            null, AiDraftController.Kind.INTRO, null, "活动", null, null, null, null,
            null, null, null))).status.value());
    }

    private void authenticate(UserAccount user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user.id, null, List.of()));
    }
}

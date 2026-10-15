package edu.demo.campusactivity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:image_visibility;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.images.directory=./target/test-activity-images"
})
class ActivityImageVisibilityTests {
    @Autowired ActivityImageStore images;
    @Autowired WebApplicationContext context;
    @Autowired ActivityImageReadController reader;
    @Autowired MiniappActivityImageController miniappReader;
    @Autowired ActivityService activities;
    @Autowired ActivityCancellationService cancellations;
    @Autowired UserRepository users;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void draftCoverIsPrivateAndPublishedCoverRequiresStudentOrStaff() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ImageIO.write(image, "png", data);
        String url = images.save(new MockMultipartFile("file", "cover.png", "image/png", data.toByteArray()));
        String filename = url.substring(8);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/miniapp" + url)).andExpect(status().isUnauthorized());
        assertEquals(401, assertThrows(ApiException.class, () -> reader.read(filename)).status.value());

        UserAccount admin = users.saveAndFlush(new UserAccount("图片管理员", "image-admin@test.local",
            "unused", UserAccount.Role.ADMIN));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin.id, null, List.of()));
        Instant now = Instant.now();
        ActivityService.ActivityInput input = new ActivityService.ActivityInput("图片测试活动", "正文", "摘要",
            url, "报告厅", 108.93, 34.34, now.plusSeconds(3600), now.plusSeconds(7200),
            now.minusSeconds(60), now.plusSeconds(1800), now.minusSeconds(60), now.plusSeconds(3600), 20);
        ActivityView draft = activities.createForAdmin(input);
        assertEquals(MediaType.IMAGE_PNG, reader.read(filename).getHeaders().getContentType());
        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ApiException.class, () -> reader.read(filename)).status.value());

        UserAccount student = users.saveAndFlush(new UserAccount("图片学生", "image-student@test.local",
            "unused", UserAccount.Role.STUDENT));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(student.id, null, List.of()));
        assertEquals(404, assertThrows(ApiException.class, () -> miniappReader.read(filename)).status.value());
        SecurityContextHolder.clearContext();

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin.id, null, List.of()));
        activities.publishOwnForAdmin(draft.id());
        SecurityContextHolder.clearContext();
        assertEquals(401, assertThrows(ApiException.class, () -> reader.read(filename)).status.value());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(student.id, null, List.of()));
        assertTrue(miniappReader.read(filename).getBody().exists());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(admin.id, null, List.of()));
        cancellations.emergency(draft.id(), "场地故障");
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(student.id, null, List.of()));
        assertTrue(miniappReader.read(filename).getBody().exists());
    }
}

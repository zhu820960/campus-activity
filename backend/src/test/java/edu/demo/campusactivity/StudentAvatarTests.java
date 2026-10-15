package edu.demo.campusactivity;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:student_avatar;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.wechat.app-id=test-miniapp",
    "app.avatars.directory=./target/test-avatars"
})
class StudentAvatarTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired MiniappAuthService auth;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private byte[] png(int color) throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    @Test
    void avatarIsPrivateAndCanBeReplacedAndRemoved() throws Exception {
        UserAccount owner = users.saveAndFlush(new UserAccount("头像学生", "avatar-owner@test.local",
            passwords.encode("student123"), UserAccount.Role.STUDENT));
        UserAccount other = users.saveAndFlush(new UserAccount("其他学生", "avatar-other@test.local",
            passwords.encode("student123"), UserAccount.Role.STUDENT));
        String ownerToken = "Bearer " + auth.bind("avatar-owner-openid", owner.email, "student123").token();
        String otherToken = "Bearer " + auth.bind("avatar-other-openid", other.email, "student123").token();

        mvc().perform(get("/api/miniapp/my/avatar")).andExpect(status().isUnauthorized());
        mvc().perform(get("/api/miniapp/my/avatar").header("Authorization", ownerToken))
            .andExpect(status().isNotFound());
        mvc().perform(multipart("/api/miniapp/my/avatar").file(new MockMultipartFile(
            "file", "original.png", "image/png", png(0x112233))).header("Authorization", ownerToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.hasAvatar").value(true));
        String original = users.findById(owner.id).orElseThrow().avatarKey;
        assertTrue(Files.exists(Path.of("./target/test-avatars", original)));
        mvc().perform(get("/api/miniapp/my/avatar").header("Authorization", ownerToken))
            .andExpect(status().isOk()).andExpect(result -> {
                assertTrue(result.getResponse().getContentType().startsWith(MediaType.IMAGE_PNG_VALUE));
                assertTrue(result.getResponse().getContentAsByteArray().length > 0);
            });
        mvc().perform(get("/api/miniapp/my/avatar").header("Authorization", otherToken))
            .andExpect(status().isNotFound());
        mvc().perform(get("/images/" + original)).andExpect(status().isUnauthorized());

        mvc().perform(multipart("/api/miniapp/my/avatar").file(new MockMultipartFile(
            "file", "replacement.png", "image/png", png(0x445566))).header("Authorization", ownerToken))
            .andExpect(status().isOk());
        String replacement = users.findById(owner.id).orElseThrow().avatarKey;
        assertFalse(original.equals(replacement));
        assertFalse(Files.exists(Path.of("./target/test-avatars", original)));
        mvc().perform(delete("/api/miniapp/my/avatar").header("Authorization", ownerToken))
            .andExpect(status().isNoContent());
        assertNull(users.findById(owner.id).orElseThrow().avatarKey);
        assertFalse(Files.exists(Path.of("./target/test-avatars", replacement)));
        mvc().perform(get("/api/miniapp/my/avatar").header("Authorization", ownerToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void fakeImageAndOversizedImageAreRejected() throws Exception {
        UserAccount owner = users.saveAndFlush(new UserAccount("验证学生", "avatar-validation@test.local",
            passwords.encode("student123"), UserAccount.Role.STUDENT));
        String token = "Bearer " + auth.bind("avatar-validation-openid", owner.email, "student123").token();
        mvc().perform(multipart("/api/miniapp/my/avatar").file(new MockMultipartFile(
            "file", "fake.png", "image/png", "not an image".getBytes())).header("Authorization", token))
            .andExpect(status().isBadRequest());
        mvc().perform(multipart("/api/miniapp/my/avatar").file(new MockMultipartFile(
            "file", "large.png", "image/png", new byte[2 * 1024 * 1024 + 1]))
            .header("Authorization", token)).andExpect(status().isBadRequest());
        assertNull(users.findById(owner.id).orElseThrow().avatarKey);
    }
}

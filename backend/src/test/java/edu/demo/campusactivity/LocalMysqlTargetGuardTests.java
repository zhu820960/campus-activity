package edu.demo.campusactivity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LocalMysqlTargetGuardTests {
    private final LocalMysqlTargetGuard guard = new LocalMysqlTargetGuard();

    @Test
    void regularMysqlProfileOnlyAcceptsDevelopmentDatabase() {
        assertDoesNotThrow(() -> guard.postProcessEnvironment(environment(
            "jdbc:mysql://127.0.0.1:3306/campus_activity_miniapp_dev"), null));
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(environment(
            "jdbc:mysql://127.0.0.1:3306/campus_activity_miniapp_other"), null));
    }

    @Test
    void cloneRequiresAllValidationSafeguards() {
        String url = "jdbc:mysql://127.0.0.1:3306/campus_activity_miniapp_stage2_rehearsal_20261013_113503";
        MockEnvironment safe = environment(url)
            .withProperty("STAGE2_CLONE_VALIDATION", "true")
            .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
            .withProperty("spring.main.web-application-type", "none");
        assertDoesNotThrow(() -> guard.postProcessEnvironment(safe, null));
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(
            environment(url).withProperty("STAGE2_CLONE_VALIDATION", "true"), null));
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(
            environment(url).withProperty("STAGE2_CLONE_VALIDATION", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "update")
                .withProperty("spring.main.web-application-type", "none"), null));
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(
            environment(url).withProperty("STAGE2_CLONE_VALIDATION", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.main.web-application-type", "servlet"), null));
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(
            environment(url).withProperty("STAGE2_CLONE_VALIDATION", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("APP_BOOTSTRAP_ADMIN_EMAIL", "admin@example.test"), null));
    }

    @Test
    void cloneWebPreviewRequiresLoopbackAndFixedPort() {
        String url = "jdbc:mysql://127.0.0.1:3306/campus_activity_miniapp_stage2_rehearsal_20261013_113503";
        MockEnvironment preview = environment(url)
            .withProperty("STAGE2_CLONE_PREVIEW", "true")
            .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
            .withProperty("spring.main.web-application-type", "servlet")
            .withProperty("server.address", "127.0.0.1")
            .withProperty("server.port", "18082");
        assertDoesNotThrow(() -> guard.postProcessEnvironment(preview, null));
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(
            environment(url).withProperty("STAGE2_CLONE_PREVIEW", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.main.web-application-type", "servlet")
                .withProperty("server.address", "0.0.0.0")
                .withProperty("server.port", "18082"), null));
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(
            environment(url).withProperty("STAGE2_CLONE_PREVIEW", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.main.web-application-type", "servlet")
                .withProperty("server.address", "127.0.0.1")
                .withProperty("server.port", "8080"), null));
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(
            environment(url).withProperty("STAGE2_CLONE_PREVIEW", "true")
                .withProperty("spring.jpa.hibernate.ddl-auto", "update")
                .withProperty("spring.main.web-application-type", "servlet")
                .withProperty("server.address", "127.0.0.1")
                .withProperty("server.port", "18082"), null));
    }

    private MockEnvironment environment(String url) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("mysql");
        return environment.withProperty("DB_URL", url);
    }
}

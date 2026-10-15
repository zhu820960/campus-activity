package edu.demo.campusactivity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

public final class LocalMysqlTargetGuard implements EnvironmentPostProcessor {
    private static final String TARGET = "jdbc:mysql://127.0.0.1:3306/campus_activity_miniapp_dev";
    private static final String CLONE_PREFIX = "jdbc:mysql://127.0.0.1:3306/";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles("mysql")) return;
        String url = environment.getProperty("DB_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("DB_URL is required for the mysql profile");
        }
        if (!url.equals(TARGET) && !url.startsWith(TARGET + "?")
            && !isReadOnlyCloneValidation(environment, url) && !isLocalClonePreview(environment, url)) {
            throw new IllegalStateException("DB_URL must target the local campus_activity_miniapp_dev database");
        }
    }

    private boolean isReadOnlyCloneValidation(ConfigurableEnvironment environment, String url) {
        if (!url.startsWith(CLONE_PREFIX)) return false;
        String database = url.substring(CLONE_PREFIX.length()).split("\\?", 2)[0];
        return database.matches("campus_activity_miniapp_stage2_rehearsal_[0-9]{8}_[0-9]{6}")
            && "true".equals(environment.getProperty("STAGE2_CLONE_VALIDATION"))
            && "validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
            && "none".equals(environment.getProperty("spring.main.web-application-type"))
            && environment.getProperty("APP_BOOTSTRAP_ADMIN_EMAIL", "").isBlank()
            && environment.getProperty("APP_BOOTSTRAP_ADMIN_PASSWORD", "").isBlank();
    }

    private boolean isLocalClonePreview(ConfigurableEnvironment environment, String url) {
        if (!url.startsWith(CLONE_PREFIX)) return false;
        String database = url.substring(CLONE_PREFIX.length()).split("\\?", 2)[0];
        return database.matches("campus_activity_miniapp_stage2_rehearsal_[0-9]{8}_[0-9]{6}")
            && "true".equals(environment.getProperty("STAGE2_CLONE_PREVIEW"))
            && "validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
            && "servlet".equals(environment.getProperty("spring.main.web-application-type"))
            && "127.0.0.1".equals(environment.getProperty("server.address"))
            && "18082".equals(environment.getProperty("server.port"))
            && environment.getProperty("APP_BOOTSTRAP_ADMIN_EMAIL", "").isBlank()
            && environment.getProperty("APP_BOOTSTRAP_ADMIN_PASSWORD", "").isBlank();
    }
}

package edu.demo.campusactivity;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class BootstrapAdmin {
    @Bean
    ApplicationRunner ensureAdmin(UserRepository users, PasswordEncoder passwords,
                                  @Value("${app.bootstrap-admin.email:}") String email,
                                  @Value("${app.bootstrap-admin.password:}") String password) {
        return args -> {
            if (email.isBlank() || password.isBlank()) return;
            if (password.length() < 8) throw new IllegalStateException("管理员初始密码至少需要 8 位");
            String normalized = email.trim().toLowerCase(Locale.ROOT);
            users.findByEmailIgnoreCase(normalized).ifPresentOrElse(user -> {
                if (!user.roles.contains(UserAccount.Role.ADMIN)) {
                    throw new IllegalStateException("管理员邮箱已被普通账号占用");
                }
            }, () -> users.save(new UserAccount("系统管理员", normalized,
                passwords.encode(password), UserAccount.Role.ADMIN)));
        };
    }
}

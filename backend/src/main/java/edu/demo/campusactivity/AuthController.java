package edu.demo.campusactivity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    record LoginRequest(@NotBlank String email, @NotBlank String password) {}
    record UserView(Long id, String name, String email, String studentNumber, Set<UserAccount.Role> roles, boolean hasAvatar) {
        static UserView of(UserAccount user) {
            return new UserView(user.getId(), user.getName(), user.getEmail(), user.getStudentNumber(),
                Set.copyOf(user.roles), user.getAvatarKey() != null);
        }
    }

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final SecurityContextRepository contexts;
    private final CurrentUser current;

    AuthController(UserRepository users, PasswordEncoder passwords,
                   SecurityContextRepository contexts, CurrentUser current) {
        this.users = users;
        this.passwords = passwords;
        this.contexts = contexts;
        this.current = current;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping("/login")
    UserView login(@Valid @RequestBody LoginRequest request,
                   HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String account = request.email().trim();
        UserAccount user;
        if ("admin".equalsIgnoreCase(account)) {
            List<UserAccount> admins = users.findAll().stream()
                .filter(candidate -> candidate.roles.contains(UserAccount.Role.ADMIN)).toList();
            if (admins.size() != 1) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
            }
            user = admins.get(0);
        } else {
            user = (account.matches("[0-9]{14}") ? users.findByStudentNumber(account)
                : account.contains("@") ? users.findByEmailIgnoreCase(account)
                : users.findByLoginNameIgnoreCase(account))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误"));
        }
        if (user.passwordHash == null || !passwords.matches(request.password(), user.passwordHash)
            || (!user.roles.contains(UserAccount.Role.ADMIN)
                && !user.roles.contains(UserAccount.Role.ORGANIZER))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "账号或密码错误");
        }
        servletRequest.getSession(true);
        servletRequest.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        var authentication = new UsernamePasswordAuthenticationToken(user.getId(), null,
            user.roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList());
        authentication.setDetails(WebSessionCredentials.capture(user.passwordHash));
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, servletRequest, servletResponse);
        return UserView.of(user);
    }

    @GetMapping("/me")
    UserView me() {
        return UserView.of(current.get());
    }
}

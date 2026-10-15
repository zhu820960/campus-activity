package edu.demo.campusactivity;

import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class CurrentUser {
    private final UserRepository users;

    CurrentUser(UserRepository users) {
        this.users = users;
    }

    UserAccount get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long id)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        UserAccount user = users.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "账号不存在"));
        user = org.hibernate.Hibernate.unproxy(user, UserAccount.class);
        // A later password activation must never revive a session revoked by unbinding.
        if ("WEB_SESSION".equals(auth.getDetails())
            || (auth.getDetails() instanceof WebSessionCredentials credentials
                && !credentials.matches(user.passwordHash))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Web 登录已失效，请重新登录");
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String role = authority.getAuthority();
            if (role.startsWith("ROLE_") && user.getRoles().stream()
                .noneMatch(currentRole -> role.equals("ROLE_" + currentRole.name()))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "权限已变更，请重新登录");
            }
        }
        return user;
    }

    UserAccount require(UserAccount.Role... roles) {
        UserAccount user = get();
        if (Set.of(roles).stream().noneMatch(user.getRoles()::contains)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "没有操作权限");
        }
        return user;
    }

    MiniappAttribution miniappAttribution() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getDetails() instanceof MiniappAttribution attribution)) return null;
        if (!attribution.studentId().equals(auth.getPrincipal())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "小程序身份已失效");
        }
        return attribution;
    }

    void owns(Activity activity) {
        UserAccount user = get();
        if (!activity.organizer.getId().equals(user.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只能管理自己的活动");
        }
    }
}

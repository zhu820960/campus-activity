package edu.demo.campusactivity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class MiniappBearerFilter extends OncePerRequestFilter {
    private final MiniappAuthService auth;

    MiniappBearerFilter(MiniappAuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!header.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "小程序登录凭证无效");
            return;
        }
        MiniappAuthService.AuthenticatedStudent authenticated;
        try {
            authenticated = auth.authenticateWithAttribution(header.substring(7));
        } catch (ApiException error) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "小程序登录已失效");
            return;
        }
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            var authentication = new UsernamePasswordAuthenticationToken(authenticated.student().getId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
            authentication.setDetails(authenticated.attribution());
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

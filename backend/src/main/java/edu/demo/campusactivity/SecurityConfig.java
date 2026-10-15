package edu.demo.campusactivity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    @Order(1)
    @ConditionalOnWebApplication
    SecurityFilterChain miniappSecurityFilterChain(HttpSecurity http, MiniappAuthService miniappAuth) throws Exception {
        http.securityMatcher("/api/miniapp/**")
            .securityContext(config -> config.securityContextRepository(new NullSecurityContextRepository()))
            .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(config -> config.disable())
            .authorizeHttpRequests(config -> config
                .requestMatchers(HttpMethod.GET, "/api/miniapp/auth/appeals/availability").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/miniapp/auth/login", "/api/miniapp/auth/bind",
                    "/api/miniapp/auth/bind-student", "/api/miniapp/auth/appeals/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(new MiniappBearerFilter(miniappAuth), UsernamePasswordAuthenticationFilter.class)
            .formLogin(config -> config.disable())
            .httpBasic(config -> config.disable())
            .logout(config -> config.disable())
            .exceptionHandling(config -> config.authenticationEntryPoint((request, response, error) ->
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "请先登录小程序")));
        return http.build();
    }

    @Bean
    @Order(2)
    @ConditionalOnWebApplication
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts) throws Exception {
        http
            .securityContext(config -> config.securityContextRepository(contexts))
            .csrf(config -> config.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .authorizeHttpRequests(config -> config
                .requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll()
                .anyRequest().authenticated())
            .formLogin(config -> config.disable())
            .httpBasic(config -> config.disable())
            .logout(config -> config.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .exceptionHandling(config -> config.authenticationEntryPoint((request, response, error) -> {
                response.sendError(HttpStatus.UNAUTHORIZED.value(), "请先登录");
            }));
        return http.build();
    }
}

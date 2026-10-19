package com.personaltrader.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration
public class SecurityConfiguration {
    @Bean
    UserDetailsService localUser(@Value("${APP_LOGIN_USERNAME:user}") String username,
                                @Value("${APP_LOGIN_PASSWORD:}") String password,
                                @Value("${APP_LOGIN_MIN_PASSWORD_LENGTH:12}") int minimumLength) {
        // Default remains 12. A local owner may explicitly choose a shorter password for this installation.
        if (minimumLength < 8 || minimumLength > 72 || username.isBlank() || password.length() < minimumLength
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("로컬 로그인 이름과 최소 길이 설정(8~72자)에 맞는 비밀번호를 .env.login에 설정하세요.");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password("{bcrypt}" + new BCryptPasswordEncoder(12).encode(password)).roles("OWNER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        LoginThrottleFilter throttle = new LoginThrottleFilter();
        SimpleUrlAuthenticationSuccessHandler success = new SimpleUrlAuthenticationSuccessHandler("/");
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/error").permitAll()
                .anyRequest().hasRole("OWNER"))
            .formLogin(form -> form.loginPage("/login").permitAll()
                .successHandler((request, response, authentication) -> {
                    throttle.succeeded(request); success.onAuthenticationSuccess(request, response, authentication);
                })
                .failureHandler((request, response, exception) -> {
                    throttle.failed(request); response.sendRedirect("/login?error");
                }))
            // Used for local authenticated JSON diagnostics; never expose this HTTP listener publicly.
            .httpBasic(basic -> basic.authenticationEntryPoint((request, response, exception) -> {
                if (request.getHeader("Authorization") != null) throttle.failed(request);
                response.setHeader("WWW-Authenticate", "Basic realm=\"Local paper dashboard\"");
                jsonError(response, 401, "LOGIN_REQUIRED");
            }))
            .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                    (request, response, exception) -> jsonError(response, 401, "LOGIN_REQUIRED"),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**")))
            .requestCache(cache -> cache.requestCache(new org.springframework.security.web.savedrequest.NullRequestCache()))
            .csrf(Customizer.withDefaults())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout").deleteCookies("JSESSIONID"))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.migrateSession()))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; "
                        + "img-src 'self' data:; connect-src 'self'; font-src 'self'; object-src 'none'; "
                        + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .permissionsPolicyHeader(policy -> policy.policy("camera=(), microphone=(), geolocation=(), payment=()")))
            .addFilterBefore(throttle, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static void jsonError(HttpServletResponse response, int status, String code) throws java.io.IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}

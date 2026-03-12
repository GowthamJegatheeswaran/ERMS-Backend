package com.uoj.equipment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    public SecurityConfig(JwtFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .cors().and()
                .csrf().disable()
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ── Public endpoints (no auth required) ───────────────────────────
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/signup",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/password/**",

                                /*
                                 * FIX BUG 8:
                                 * These two endpoints were missing from permitAll.
                                 *
                                 * /api/auth/verify-email (GET) is the link emailed to new students.
                                 * When a student clicks it, they are NOT logged in yet — the request
                                 * has no JWT token. Without permitAll, Spring returns 401 and the
                                 * email verification flow is completely broken.
                                 *
                                 * /api/auth/resend-verification (POST) is used on the login page
                                 * to resend the verification email — also not authenticated.
                                 */
                                "/api/auth/verify-email",
                                "/api/auth/resend-verification"
                        ).permitAll()

                        // ── Role-based access ──────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST, "/api/student/requests")
                        .hasAnyRole("STUDENT", "STAFF", "LECTURER", "HOD")
                        .requestMatchers(HttpMethod.GET, "/api/student/requests")
                        .hasAnyRole("STUDENT", "STAFF", "LECTURER", "HOD")

                        // Lecturer uses the same accept/return flow as students for their own requests
                        .requestMatchers(HttpMethod.POST, "/api/student/requests/*/accept-issue")
                        .hasAnyRole("STUDENT", "STAFF", "LECTURER", "HOD")
                        .requestMatchers(HttpMethod.POST, "/api/student/request-items/*/accept-issue")
                        .hasAnyRole("STUDENT", "STAFF", "LECTURER", "HOD")
                        .requestMatchers(HttpMethod.POST, "/api/student/requests/*/return")
                        .hasAnyRole("STUDENT", "STAFF", "LECTURER", "HOD")
                        .requestMatchers(HttpMethod.POST, "/api/student/request-items/*/return")
                        .hasAnyRole("STUDENT", "STAFF", "LECTURER", "HOD")

                        .requestMatchers("/api/student/**", "/api/staff/**")
                        .hasAnyRole("STUDENT", "STAFF")

                        .requestMatchers("/api/lecturer/**")
                        .hasAnyRole("LECTURER", "HOD")

                        .requestMatchers("/api/to/**")
                        .hasRole("TO")

                        .requestMatchers("/api/hod/**")
                        .hasRole("HOD")

                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")

                        // Common authenticated endpoints
                        .requestMatchers("/api/common/**")
                        .authenticated()

                        .anyRequest()
                        .authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOrigin("https://equipment-request-frontend-production.up.railway.app");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
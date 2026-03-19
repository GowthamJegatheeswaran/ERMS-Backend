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
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/signup",
                                "/api/auth/signup-verify",
                                "/api/auth/forgot-password",
                                "/api/auth/verify-otp",
                                "/api/auth/reset-password",
                                "/api/auth/verify-email",
                                "/api/auth/resend-verification"
                        ).permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/student/requests")
                        .hasAnyRole("STUDENT", "STAFF", "LECTURER", "HOD")
                        .requestMatchers(HttpMethod.GET, "/api/student/requests")
                        .hasAnyRole("STUDENT", "STAFF", "LECTURER", "HOD")

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

        // Updated to new frontend URL
        config.addAllowedOrigin("https://erms.up.railway.app");

        config.addAllowedHeader("*");
        config.addAllowedMethod("*");

        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
package com.example.reviewbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The webhook endpoint authenticates each delivery with its HMAC signature, so it is open here.
 * The dashboard shows repository names and spend, so it sits behind HTTP Basic auth
 * ({@code DASHBOARD_USERNAME} / {@code DASHBOARD_PASSWORD}).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    public static final String WEBHOOK_PATH = "/api/github/webhook";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, WEBHOOK_PATH).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/css/**", "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(WEBHOOK_PATH))
                .httpBasic(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}

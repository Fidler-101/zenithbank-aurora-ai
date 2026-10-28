package com.zenithbank.banking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * Security Config — Banking Service
 *
 * This service sits behind the API Gateway which already validates
 * the JWT and injects X-Customer-Id, X-Customer-Email, X-Customer-Role
 * as trusted headers. We do NOT re-validate the JWT here.
 *
 * In production, add network policy / mTLS to ensure only the
 * gateway can reach this service directly (not external clients).
 *
 * All endpoints require the X-Customer-Id header (checked in controller).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().permitAll() // Auth enforced at gateway; controller checks X-Customer-Id
            )
            .csrf(csrf -> csrf.disable())
            .httpBasic(h -> h.disable())
            .formLogin(f -> f.disable());

        return http.build();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}

package com.zenithbank.customer.config;
import com.zenithbank.customer.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.util.logging.Logger;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private static final Logger log = Logger.getLogger(SecurityConfig.class.getName());

    @Value("${app.security.enabled:true}")
    private boolean securityEnabled;

    private final JwtCookieFilter jwtCookieFilter;

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (!securityEnabled) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/register", "/register/**",
                    "/api/customers/register", "/api/customers/register/**",
                    "/api/customers/login", "/api/customers/verify-otp",
                    "/api/customers/resend-otp",
                    "/login", "/verify-otp", "/resend-otp",
                    "/pending-approval",
                    "/css/**", "/js/**", "/images/**",
                    "/actuator/**"
                ).permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            );
        }

        http.addFilterBefore(jwtCookieFilter, UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf.disable())
            .formLogin(f -> f.disable())
            .httpBasic(h -> h.disable());

        return http.build();
    }

    @Component
    @RequiredArgsConstructor
    public static class JwtCookieFilter extends OncePerRequestFilter {
        private final JwtService jwtService;

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {
            String token = extractTokenFromCookie(request);
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    Claims claims = jwtService.validateToken(token);
                    String role = claims.get("role", String.class);
                    var auth = new UsernamePasswordAuthenticationToken(
                            claims.getSubject(), null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (Exception e) {
                    log.fine("JWT validation failed: " + e.getMessage());
                }
            }
            chain.doFilter(request, response);
        }

        private String extractTokenFromCookie(HttpServletRequest request) {
            if (request.getCookies() == null) return null;
            return Arrays.stream(request.getCookies())
                    .filter(c -> "jwt_token".equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }
    }
}

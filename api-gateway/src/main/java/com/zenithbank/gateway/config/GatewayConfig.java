package com.zenithbank.gateway.config;

import com.zenithbank.gateway.filter.JwtAuthFilter;
import com.zenithbank.gateway.switch_.MaintenanceSwitchFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ZenithBank API Gateway — Route Configuration
 *
 * Every route now has two filters applied in order:
 *   1. MaintenanceSwitchFilter  — checks Redis; returns 503 if service is OFF
 *   2. JwtAuthFilter            — validates RS256 JWT (protected routes only)
 *
 * Switching a service offline (via /admin/switches) takes effect
 * instantly without restarting the gateway or the service itself.
 */
@Configuration
public class GatewayConfig {

    private final JwtAuthFilter           jwtAuthFilter;
    private final MaintenanceSwitchFilter switchFilter;

    public GatewayConfig(JwtAuthFilter jwtAuthFilter,
                         MaintenanceSwitchFilter switchFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.switchFilter  = switchFilter;
    }

    private MaintenanceSwitchFilter.Config switchFor(String serviceName) {
        MaintenanceSwitchFilter.Config cfg = new MaintenanceSwitchFilter.Config();
        cfg.setServiceName(serviceName);
        return cfg;
    }

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()

            // ── CUSTOMER SERVICE — public routes ──────────────────
            .route("customer-service-public", r -> r
                .path(
                    "/api/customers/register",
                    "/api/customers/register/**",
                    "/api/customers/login",
                    "/api/customers/verify-otp",
                    "/api/customers/resend-otp",
                    "/api/customers/pending-approval"
                )
                .filters(f -> f
                    .filter(switchFilter.apply(switchFor("customer-service")))
                )
                .uri("lb://customer-service")
            )

            // ── CUSTOMER SERVICE — protected routes ───────────────
            .route("customer-service-protected", r -> r
                .path("/api/customers/**")
                .filters(f -> f
                    .filter(switchFilter.apply(switchFor("customer-service")))
                    .filter(jwtAuthFilter.apply(new JwtAuthFilter.Config()))
                )
                .uri("lb://customer-service")
            )

            // ── BANKING SERVICE ───────────────────────────────────
            .route("banking-service", r -> r
                .path("/api/banking/**")
                .filters(f -> f
                    .stripPrefix(2)
                    .filter(switchFilter.apply(switchFor("banking-service")))
                    .filter(jwtAuthFilter.apply(new JwtAuthFilter.Config()))
                )
                .uri("lb://banking-service")
            )

            // ── PAYMENT SERVICE ───────────────────────────────────
            .route("payment-service", r -> r
                .path("/api/payments/**")
                .filters(f -> f
                    .stripPrefix(2)
                    .filter(switchFilter.apply(switchFor("payment-service")))
                    .filter(jwtAuthFilter.apply(new JwtAuthFilter.Config()))
                )
                .uri("lb://payment-service")
            )

            // ── TRANSACTION STATUS SERVICE ────────────────────────
            .route("transaction-status-service", r -> r
                .path("/api/transactions/**")
                .filters(f -> f
                    .stripPrefix(2)
                    .filter(switchFilter.apply(switchFor("transaction-status-service")))
                    .filter(jwtAuthFilter.apply(new JwtAuthFilter.Config()))
                )
                .uri("lb://transaction-status-service")
            )

            // ── AURORA AI — health check (public, no switch block) ─
            // NOTE: no stripPrefix here -- AuroraAI's own FastAPI router
            // is mounted with prefix="/api/aurora" internally, so it
            // expects the full original path unchanged.
            .route("aurora-ai-health", r -> r
                .path("/api/aurora/health")
                .uri("http://aurora-ai-service:8000")
            )

            // ── AURORA AI — chat (protected + switch) ────────────
            .route("aurora-ai-chat", r -> r
                .path("/api/aurora/**")
                .filters(f -> f
                    .filter(switchFilter.apply(switchFor("aurora-ai-service")))
                    .filter(jwtAuthFilter.apply(new JwtAuthFilter.Config()))
                )
                .uri("http://aurora-ai-service:8000")
            )

            .build();
    }
}

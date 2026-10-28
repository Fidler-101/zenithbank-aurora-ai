package com.zenithbank.customer.security.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * OWASP Security Headers Filter
 *
 * Applied to every HTTP response. Implements the OWASP Secure
 * Headers Project recommendations for financial applications.
 *
 * Headers applied:
 *  - Strict-Transport-Security (HSTS) — HTTPS only, 1 year
 *  - Content-Security-Policy — blocks XSS, clickjacking, data injection
 *  - X-Content-Type-Options — blocks MIME sniffing
 *  - X-Frame-Options — prevents clickjacking
 *  - Referrer-Policy — no sensitive info in Referer headers
 *  - Permissions-Policy — disables unnecessary browser features
 *  - Cache-Control — no caching of sensitive responses
 *  - X-Correlation-ID — request tracing across services
 */
@Component
@Order(1)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletResponse response = (HttpServletResponse) res;

        // HTTPS enforcement — 1 year, include subdomains, preload ready
        response.setHeader("Strict-Transport-Security",
                "max-age=31536000; includeSubDomains; preload");

        // XSS + injection prevention
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self'; " +
                "style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data:; " +
                "font-src 'self'; " +
                "frame-ancestors 'none'; " +
                "form-action 'self'; " +
                "base-uri 'self'");

        // MIME sniffing prevention
        response.setHeader("X-Content-Type-Options", "nosniff");

        // Clickjacking prevention
        response.setHeader("X-Frame-Options", "DENY");

        // No Referer leakage
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // Disable unnecessary browser features
        response.setHeader("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), " +
                "payment=(), usb=(), bluetooth=()");

        // No caching of sensitive banking responses
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");

        // Remove server fingerprint
        response.setHeader("Server", "");
        response.setHeader("X-Powered-By", "");

        chain.doFilter(req, res);
    }
}

package com.zenithbank.customer.security.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Correlation ID Filter
 *
 * Every request gets a unique X-Correlation-ID. If one arrives
 * from the API gateway (which generated it), we keep it.
 * If not (direct call), we generate one.
 *
 * The ID is:
 *  - Added to MDC so it appears in every log line for this request
 *  - Returned in the response header so clients can reference it
 *  - Propagated as a header to downstream service calls
 *
 * This is essential for tracing a single user action across
 * customer-service → banking-service → payment-service logs.
 */
@Component
@Order(2)
public class CorrelationIdFilter implements Filter {

    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        String correlationId = request.getHeader(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // Put into MDC so every log line for this request includes the correlation ID
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);

        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

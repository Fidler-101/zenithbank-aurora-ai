package com.zenithbank.customer.security.audit;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

import java.time.LocalDateTime;

/**
 * Security Audit Service — structured security event logging.
 *
 * Every significant security event is logged in a structured format
 * so it can be ingested into a SIEM (Security Information and
 * Event Management) system like Splunk, Elastic, or AWS Security Hub.
 *
 * Event types logged:
 *  - LOGIN_SUCCESS / LOGIN_FAILURE
 *  - ACCOUNT_LOCKED / ACCOUNT_UNLOCKED
 *  - LOGOUT / TOKEN_REVOKED
 *  - REGISTRATION_SUCCESS / OTP_VERIFIED
 *  - ADMIN_ACTION (approve/reject/suspend)
 *  - SUSPICIOUS_ACTIVITY (IP rate limit exceeded)
 *  - PASSWORD_CHANGE
 */
@Service
public class SecurityAuditService {
    private static final Logger log = Logger.getLogger(SecurityAuditService.class.getName());


    public enum SecurityEvent {
        LOGIN_SUCCESS, LOGIN_FAILURE, ACCOUNT_LOCKED,
        LOGOUT, TOKEN_REVOKED,
        REGISTRATION_SUCCESS, OTP_VERIFIED, OTP_FAILED,
        ADMIN_APPROVE, ADMIN_REJECT, ADMIN_SUSPEND,
        SUSPICIOUS_ACTIVITY, IP_RATE_LIMITED,
        PASSWORD_CHANGE, SESSION_EXPIRED
    }

    @Async
    public void log(SecurityEvent event, String email, String ipAddress, String detail) {
        // Structured JSON log — ingested by log aggregators (ELK, Splunk, CloudWatch)
        String entry = String.format(
            "{\"event\":\"%s\",\"email\":\"%s\",\"ip\":\"%s\",\"detail\":\"%s\",\"timestamp\":\"%s\"}",
            event.name(), sanitise(email), sanitise(ipAddress), sanitise(detail),
            LocalDateTime.now()
        );

        if (isCritical(event)) {
            log.severe("[SECURITY AUDIT] " + entry);
        } else if (isWarning(event)) {
            log.warning("[SECURITY AUDIT] " + entry);
        } else {
            log.info("[SECURITY AUDIT] " + entry);
        }
    }

    @Async
    public void log(SecurityEvent event, String email, String ipAddress) {
        log(event, email, ipAddress, "");
    }

    private boolean isCritical(SecurityEvent event) {
        return event == SecurityEvent.ACCOUNT_LOCKED
            || event == SecurityEvent.SUSPICIOUS_ACTIVITY
            || event == SecurityEvent.IP_RATE_LIMITED;
    }

    private boolean isWarning(SecurityEvent event) {
        return event == SecurityEvent.LOGIN_FAILURE
            || event == SecurityEvent.OTP_FAILED;
    }

    // Prevent log injection attacks
    private String sanitise(String input) {
        if (input == null) return "";
        return input.replaceAll("[\r\n\t]", "").replaceAll("\"", "'");
    }
}

package com.zenithbank.customer.security.filter;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

import java.time.Duration;

/**
 * Redis-backed rate limiter using a sliding window counter.
 *
 * Two limits enforced independently:
 *  1. Per-IP  — max 20 login attempts per 15 minutes (blocks bots/scanners)
 *  2. Per-account — max 5 failed attempts before account lockout (blocks credential stuffing)
 *
 * Lockout uses exponential backoff:
 *  5 failures  → 5 min lockout
 *  10 failures → 30 min lockout
 *  15+ failures → 24 hour lockout → triggers security alert email
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {
    private static final Logger log = Logger.getLogger(RateLimitService.class.getName());


    private final RedisTemplate<String, String> redisTemplate;

    private static final String IP_ATTEMPTS_PREFIX   = "ratelimit:ip:";
    private static final String ACCT_FAILURES_PREFIX = "ratelimit:account:";
    private static final String LOCKOUT_PREFIX       = "lockout:account:";

    private static final int MAX_IP_ATTEMPTS      = 20;
    private static final int IP_WINDOW_MINUTES    = 15;
    private static final int MAX_ACCOUNT_FAILURES = 5;

    // ── IP Rate Limiting ──────────────────────────────────────

    public boolean isIpRateLimited(String ipAddress) {
        String key   = IP_ATTEMPTS_PREFIX + ipAddress;
        Long   count = redisTemplate.opsForValue().increment(key);
        if (count == null) return false;
        if (count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(IP_WINDOW_MINUTES));
        }
        if (count > MAX_IP_ATTEMPTS) {
            log.warning("IP rate limit exceeded: " + ipAddress + " (" + count + " attempts)");
            return true;
        }
        return false;
    }

    // ── Account Lockout ───────────────────────────────────────

    public boolean isAccountLocked(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(LOCKOUT_PREFIX + email));
    }

    public void recordFailedLogin(String email) {
        String failKey = ACCT_FAILURES_PREFIX + email;
        Long   count   = redisTemplate.opsForValue().increment(failKey);
        if (count == null) return;
        if (count == 1) {
            redisTemplate.expire(failKey, Duration.ofHours(24));
        }

        if (count >= 15) {
            // Severe — lock for 24 hours
            redisTemplate.opsForValue().set(LOCKOUT_PREFIX + email, String.valueOf(count),
                Duration.ofHours(24));
            log.severe("SECURITY: Account " + email + " locked for 24h after " + count + " failures");
        } else if (count >= 10) {
            redisTemplate.opsForValue().set(LOCKOUT_PREFIX + email, String.valueOf(count),
                Duration.ofMinutes(30));
            log.warning("Account " + email + " locked for 30min after " + count + " failures");
        } else if (count >= MAX_ACCOUNT_FAILURES) {
            redisTemplate.opsForValue().set(LOCKOUT_PREFIX + email, String.valueOf(count),
                Duration.ofMinutes(5));
            log.warning("Account " + email + " locked for 5min after " + count + " failures");
        }
    }

    public void resetFailedLogins(String email) {
        redisTemplate.delete(ACCT_FAILURES_PREFIX + email);
        redisTemplate.delete(LOCKOUT_PREFIX + email);
    }

    public long getRemainingLockoutSeconds(String email) {
        Long ttl = redisTemplate.getExpire(LOCKOUT_PREFIX + email);
        return ttl != null && ttl > 0 ? ttl : 0;
    }
}

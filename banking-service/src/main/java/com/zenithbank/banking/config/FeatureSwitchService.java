package com.zenithbank.banking.config;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

/**
 * FeatureSwitchService — Banking Service
 *
 * Checks Redis for feature-level flags before executing
 * a specific feature. If the flag is OFF, the controller
 * returns 503 with a clear message instead of running
 * the business logic.
 *
 * Feature keys (set via gateway admin API):
 *   switch:feature:banking-service:health-score
 *   switch:feature:banking-service:async-transactions
 *   switch:feature:banking-service:spending
 *
 * Usage in a controller:
 *   if (!featureSwitch.isEnabled("health-score")) {
 *       return ResponseEntity.status(503)
 *           .body(ApiResponse.error("AI Health Score is temporarily unavailable."));
 *   }
 */
@Service
@RequiredArgsConstructor
public class FeatureSwitchService {
    private static final Logger log = Logger.getLogger(FeatureSwitchService.class.getName());


    private final RedisTemplate<String, String> redisTemplate;

    private static final String SERVICE = "banking-service";
    private static final String PREFIX  = "switch:feature:" + SERVICE + ":";

    public boolean isEnabled(String featureName) {
        String val = redisTemplate.opsForValue().get(PREFIX + featureName);
        boolean enabled = !"OFF".equalsIgnoreCase(val);
        if (!enabled) {
            log.info("Feature '" + featureName + "' is currently DISABLED");
        }
        return enabled;
    }
}

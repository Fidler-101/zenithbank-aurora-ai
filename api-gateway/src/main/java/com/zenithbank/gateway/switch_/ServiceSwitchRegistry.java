package com.zenithbank.gateway.switch_;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.logging.Logger;

@Component
public class ServiceSwitchRegistry {

    private static final Logger log = Logger.getLogger(ServiceSwitchRegistry.class.getName());
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public ServiceSwitchRegistry(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    private static final String S = "switch:service:";
    private static final String F = "switch:feature:";
    private static final String DEFAULT_MSG = "This service is temporarily unavailable for maintenance.";

    public boolean isServiceEnabled(String name) {
        try { return !"OFF".equalsIgnoreCase(redisTemplate.opsForValue().get(S + name).block()); }
        catch (Exception e) { return true; }
    }

    public void setServiceEnabled(String name, boolean on, String msg, String eta) {
        redisTemplate.opsForValue().set(S + name, on ? "ON" : "OFF").block();
        if (!on) {
            if (msg != null) redisTemplate.opsForValue().set(S + name + ":message", msg).block();
            if (eta != null) redisTemplate.opsForValue().set(S + name + ":eta", eta).block();
            log.warning("SERVICE SWITCH: " + name + " OFFLINE - " + msg);
        } else {
            redisTemplate.delete(S + name + ":message").block();
            redisTemplate.delete(S + name + ":eta").block();
            log.info("SERVICE SWITCH: " + name + " ONLINE");
        }
    }

    public String getMaintenanceMessage(String name) {
        try {
            String m = redisTemplate.opsForValue().get(S + name + ":message").block();
            return m != null ? m : DEFAULT_MSG;
        } catch (Exception e) { return DEFAULT_MSG; }
    }

    public String getMaintenanceEta(String name) {
        try { return redisTemplate.opsForValue().get(S + name + ":eta").block(); }
        catch (Exception e) { return null; }
    }

    public boolean isFeatureEnabled(String svc, String feat) {
        try { return !"OFF".equalsIgnoreCase(redisTemplate.opsForValue().get(F + svc + ":" + feat).block()); }
        catch (Exception e) { return true; }
    }

    public void setFeatureEnabled(String svc, String feat, boolean on) {
        redisTemplate.opsForValue().set(F + svc + ":" + feat, on ? "ON" : "OFF").block();
        log.info("FEATURE SWITCH: " + svc + "/" + feat + " " + (on ? "ON" : "OFF"));
    }

    public Map<String, Object> getAllSwitches() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String svc : List.of("customer-service", "banking-service", "payment-service",
                                   "transaction-status-service", "aurora-ai-service")) {
            Map<String, Object> info = new LinkedHashMap<>();
            boolean on = isServiceEnabled(svc);
            info.put("status", on ? "ONLINE" : "OFFLINE");
            info.put("enabled", on);
            if (!on) {
                info.put("message", getMaintenanceMessage(svc));
                String eta = getMaintenanceEta(svc);
                if (eta != null) info.put("eta", eta);
            }
            result.put(svc, info);
        }
        Map<String, Object> fm = new LinkedHashMap<>();
        Map.of("banking-service",   List.of("health-score", "spending"),
               "payment-service",   List.of("proof-of-payment", "recurring-payments"),
               "aurora-ai-service", List.of("chat"))
           .forEach((svc, feats) -> {
               Map<String, String> sf = new LinkedHashMap<>();
               feats.forEach(f -> sf.put(f, isFeatureEnabled(svc, f) ? "ON" : "OFF"));
               fm.put(svc, sf);
           });
        result.put("features", fm);
        return result;
    }
}

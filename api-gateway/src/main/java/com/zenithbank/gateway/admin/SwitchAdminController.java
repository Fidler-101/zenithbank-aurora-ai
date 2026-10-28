package com.zenithbank.gateway.admin;

import com.zenithbank.gateway.switch_.ServiceSwitchRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

@RestController
@RequestMapping("/admin/switches")
public class SwitchAdminController {

    private static final Logger log = Logger.getLogger(SwitchAdminController.class.getName());
    private final ServiceSwitchRegistry registry;

    public SwitchAdminController(ServiceSwitchRegistry registry) {
        this.registry = registry;
    }

    @Value("${app.admin.switch-key:zenith-admin-2026}")
    private String adminKey;

    @GetMapping
    public ResponseEntity<?> getAllSwitches(
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!adminKey.equals(key)) return forbidden();
        return ResponseEntity.ok(Map.of(
                "switches", registry.getAllSwitches(),
                "timestamp", LocalDateTime.now().toString()));
    }

    @PutMapping("/services/{name}/disable")
    public ResponseEntity<?> disable(
            @PathVariable String name,
            @RequestHeader(value = "X-Admin-Key", required = false) String key,
            @RequestBody(required = false) SwitchRequest body) {
        if (!adminKey.equals(key)) return forbidden();
        String msg = body != null ? body.message() : null;
        String eta = body != null ? body.eta() : null;
        registry.setServiceEnabled(name, false, msg, eta);
        log.warning("ADMIN: " + name + " taken OFFLINE");
        return ResponseEntity.ok(resp(name, "OFFLINE", msg, eta));
    }

    @PutMapping("/services/{name}/enable")
    public ResponseEntity<?> enable(
            @PathVariable String name,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!adminKey.equals(key)) return forbidden();
        registry.setServiceEnabled(name, true, null, null);
        log.info("ADMIN: " + name + " back ONLINE");
        return ResponseEntity.ok(resp(name, "ONLINE", null, null));
    }

    @PutMapping("/features/{svc}/{feat}/disable")
    public ResponseEntity<?> disableFeat(
            @PathVariable String svc,
            @PathVariable String feat,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!adminKey.equals(key)) return forbidden();
        registry.setFeatureEnabled(svc, feat, false);
        log.warning("ADMIN: feature " + svc + "/" + feat + " DISABLED");
        return ResponseEntity.ok(Map.of("service", svc, "feature", feat,
                "status", "DISABLED", "timestamp", LocalDateTime.now().toString()));
    }

    @PutMapping("/features/{svc}/{feat}/enable")
    public ResponseEntity<?> enableFeat(
            @PathVariable String svc,
            @PathVariable String feat,
            @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!adminKey.equals(key)) return forbidden();
        registry.setFeatureEnabled(svc, feat, true);
        log.info("ADMIN: feature " + svc + "/" + feat + " ENABLED");
        return ResponseEntity.ok(Map.of("service", svc, "feature", feat,
                "status", "ENABLED", "timestamp", LocalDateTime.now().toString()));
    }

    private Map<String, Object> resp(String svc, String status, String msg, String eta) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("service", svc);
        r.put("status", status);
        if (msg != null) r.put("message", msg);
        if (eta != null) r.put("eta", eta);
        r.put("timestamp", LocalDateTime.now().toString());
        return r;
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
    }

    public record SwitchRequest(String message, String eta) {}
}

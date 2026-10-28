package com.zenithbank.customer.controller;

import com.zenithbank.customer.dto.RegistrationRequest;
import com.zenithbank.customer.entity.Customer;
import com.zenithbank.customer.exception.CustomerException;
import com.zenithbank.customer.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerRestController {

    private final CustomerService customerService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegistrationRequest request) {
        try {
            Customer customer = customerService.register(request);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "customerId", customer.getId(),
                "email", customer.getEmail(),
                "message", "Registration successful. OTP sent to " + customer.getEmail()
            ));
        } catch (CustomerException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<?> verifyOtp(@RequestBody Map<String, String> body) {
        try {
            customerService.verifyOtp(body.get("email"), body.get("otpCode"), "127.0.0.1");
            return ResponseEntity.ok(Map.of("success", true, "message", "Email verified. Awaiting admin approval."));
        } catch (CustomerException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/admin/approve/{id}")
    public ResponseEntity<?> approve(@PathVariable Long id) {
        try {
            customerService.approveCustomer(id, "admin");
            return ResponseEntity.ok(Map.of("success", true, "message", "Customer approved successfully."));
        } catch (CustomerException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try {
            String token = customerService.login(body.get("email"), body.get("password"), "127.0.0.1", "curl");
            return ResponseEntity.ok(Map.of("success", true, "token", token));
        } catch (CustomerException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}

package com.zenithbank.customer.controller;

import jakarta.servlet.http.HttpServletRequest;

import com.zenithbank.customer.dto.RegistrationRequest;
import com.zenithbank.customer.entity.Customer;
import com.zenithbank.customer.exception.CustomerException;
import com.zenithbank.customer.service.CustomerService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.logging.Logger;

@Controller
@RequiredArgsConstructor
public class CustomerController {
    private static final Logger log = Logger.getLogger(CustomerController.class.getName());


    private final CustomerService customerService;

    // ── Registration ──────────────────────────────────────────

    @GetMapping("/register")
    public String showRegisterForm(Model model) {
        model.addAttribute("registrationRequest", new RegistrationRequest());
        model.addAttribute("provinces", SA_PROVINCES);
        return "auth/register";
    }

    @PostMapping("/register")
    public String submitRegister(
            @Valid @ModelAttribute("registrationRequest") RegistrationRequest request,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        model.addAttribute("provinces", SA_PROVINCES);
        if (bindingResult.hasErrors()) return "auth/register";

        try {
            Customer customer = customerService.register(request);
            redirectAttributes.addFlashAttribute("email", customer.getEmail());
            redirectAttributes.addFlashAttribute("success",
                "Registration successful! We've sent a 6-digit OTP to " + customer.getEmail());
            return "redirect:/verify-otp";
        } catch (CustomerException e) {
            model.addAttribute("error", e.getMessage());
            return "auth/register";
        }
    }

    // ── OTP Verification ──────────────────────────────────────

    @GetMapping("/verify-otp")
    public String showOtpForm() { return "auth/verify-otp"; }

    @PostMapping("/verify-otp")
    public String submitOtp(
            @RequestParam String email,
            @RequestParam String otpCode,
            Model model,
            RedirectAttributes redirectAttributes,
            HttpServletRequest request
    ) {
        try {
            customerService.verifyOtp(email, otpCode, request.getRemoteAddr());
            redirectAttributes.addFlashAttribute("success",
                "Email verified! Your application is under review. We'll notify you once approved.");
            return "redirect:/pending-approval";
        } catch (CustomerException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", email);
            return "auth/verify-otp";
        }
    }

    @PostMapping("/resend-otp")
    @ResponseBody
    public String resendOtp(@RequestParam String email) {
        try {
            customerService.resendOtp(email);
            return "{\"success\":true,\"message\":\"New OTP sent to " + email + "\"}";
        } catch (CustomerException e) {
            return "{\"success\":false,\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    @GetMapping("/pending-approval")
    public String pendingApproval() { return "auth/pending-approval"; }

    // ── Login ─────────────────────────────────────────────────

    @GetMapping("/login")
    public String showLoginForm() { return "auth/login"; }

    @PostMapping("/login")
    public String submitLogin(
            @RequestParam String email,
            @RequestParam String password,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        try {
            String ipAddress  = request.getRemoteAddr();
            String deviceInfo = request.getHeader("User-Agent");
            String token = customerService.login(email, password, ipAddress, deviceInfo);

            // Store JWT in HTTP-only cookie — not accessible via JavaScript
            Cookie jwtCookie = new Cookie("jwt_token", token);
            jwtCookie.setHttpOnly(true);
            jwtCookie.setSecure(false);   // set true in production (HTTPS)
            jwtCookie.setPath("/");
            jwtCookie.setMaxAge(3600);    // 1 hour
            response.addCookie(jwtCookie);

            return "redirect:/dashboard";
        } catch (CustomerException e) {
            model.addAttribute("error", e.getMessage());
            return "auth/login";
        }
    }

    // ── Logout ────────────────────────────────────────────────

    @PostMapping("/logout")
    public String logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestAttribute(required = false) Long customerId
    ) {
        if (customerId != null) customerService.logout(customerId, "", request.getRemoteAddr());

        // Clear the JWT cookie
        Cookie cookie = new Cookie("jwt_token", "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        response.addCookie(cookie);

        return "redirect:/login?logout";
    }

    // ──────────────────────────────────────────────────────────
    private static final String[] SA_PROVINCES = {
        "Gauteng", "Western Cape", "KwaZulu-Natal", "Eastern Cape",
        "Limpopo", "Mpumalanga", "North West", "Free State", "Northern Cape"
    };
}

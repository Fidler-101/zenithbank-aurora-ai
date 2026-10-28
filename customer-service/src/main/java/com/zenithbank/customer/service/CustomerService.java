package com.zenithbank.customer.service;

import com.zenithbank.customer.dto.RegistrationRequest;
import com.zenithbank.customer.entity.*;
import com.zenithbank.customer.entity.Customer.CustomerStatus;
import com.zenithbank.customer.entity.OtpVerification.OtpType;
import com.zenithbank.customer.exception.CustomerException;
import com.zenithbank.customer.repository.*;
import com.zenithbank.customer.security.audit.SecurityAuditService;
import com.zenithbank.customer.security.audit.SecurityAuditService.SecurityEvent;
import com.zenithbank.customer.security.filter.RateLimitService;
import com.zenithbank.customer.util.PasswordValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class CustomerService {
    private static final Logger log = Logger.getLogger(CustomerService.class.getName());


    private final CustomerRepository        customerRepository;
    private final OtpVerificationRepository otpRepository;
    private final RefreshTokenRepository    refreshTokenRepository;
    private final AdminAuditLogRepository   auditLogRepository;
    private final EmailService              emailService;
    private final JwtService                jwtService;
    private final BCryptPasswordEncoder     passwordEncoder;
    private final RateLimitService          rateLimitService;
    private final SecurityAuditService      auditService;
    private final PasswordValidator         passwordValidator;

    private static final int OTP_EXPIRY_MINUTES  = 10;
    private static final int REFRESH_EXPIRY_DAYS = 7;

    // ── Registration ──────────────────────────────────────────

    @Transactional
    public Customer register(RegistrationRequest request) {

        if (!request.getPassword().equals(request.getConfirmPassword()))
            throw new CustomerException("Passwords do not match.");

        // Advanced password validation
        var pwdResult = passwordValidator.validate(
            request.getPassword(), request.getFirstName(), request.getEmail()
        );
        if (!pwdResult.valid()) throw new CustomerException(pwdResult.message());

        if (customerRepository.existsByEmail(request.getEmail()))
            throw new CustomerException("An account with this email already exists.");

        if (customerRepository.existsByIdNumber(request.getIdNumber()))
            throw new CustomerException("An account with this ID number already exists.");

        if (customerRepository.existsByPhoneNumber(request.getPhoneNumber()))
            throw new CustomerException("An account with this phone number already exists.");

        Customer customer = Customer.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail().toLowerCase().trim())
                .phoneNumber(request.getPhoneNumber())
                .idNumber(request.getIdNumber())
                .dateOfBirth(request.getDateOfBirth())
                .addressLine1(request.getAddressLine1())
                .addressLine2(request.getAddressLine2())
                .city(request.getCity())
                .province(request.getProvince())
                .postalCode(request.getPostalCode())
                .country("South Africa")
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .status(CustomerStatus.PENDING)
                .role(Customer.Role.CUSTOMER)
                .emailVerified(false)
                .build();

        customer = customerRepository.save(customer);
        auditService.log(SecurityEvent.REGISTRATION_SUCCESS, customer.getEmail(), "N/A");
        issueAndSendOtp(customer, OtpType.EMAIL_VERIFICATION);
        return customer;
    }

    // ── OTP ───────────────────────────────────────────────────

    @Transactional
    public void verifyOtp(String email, String otpCode, String ipAddress) {

        Customer customer = findByEmailOrThrow(email);

        OtpVerification otp = otpRepository
                .findLatestValidOtp(customer.getId(), OtpType.EMAIL_VERIFICATION, LocalDateTime.now())
                .orElseThrow(() -> new CustomerException("OTP has expired. Please request a new one."));

        otp.incrementAttempts();

        if (!otp.getOtpCode().equals(otpCode)) {
            otpRepository.save(otp);
            auditService.log(SecurityEvent.OTP_FAILED, email, ipAddress);
            int remaining = 3 - otp.getAttempts();
            if (remaining <= 0) throw new CustomerException("Too many failed attempts. Please request a new OTP.");
            throw new CustomerException("Incorrect OTP. " + remaining + " attempt(s) remaining.");
        }

        otp.markUsed();
        otpRepository.save(otp);
        customer.setEmailVerified(true);
        customer.setStatus(CustomerStatus.OTP_VERIFIED);
        customerRepository.save(customer);

        auditService.log(SecurityEvent.OTP_VERIFIED, email, ipAddress);
        emailService.sendAdminNotification(customer);
    }

    @Transactional
    public void resendOtp(String email) {
        Customer customer = findByEmailOrThrow(email);
        if (customer.getEmailVerified()) throw new CustomerException("Email is already verified.");
        otpRepository.invalidateAllForCustomer(customer.getId(), OtpType.EMAIL_VERIFICATION);
        issueAndSendOtp(customer, OtpType.EMAIL_VERIFICATION);
    }

    // ── Login ─────────────────────────────────────────────────

    @Transactional
    public String login(String email, String password, String ipAddress, String deviceInfo) {

        // 1. IP-level rate limit — blocks bots and scanners
        if (rateLimitService.isIpRateLimited(ipAddress)) {
            auditService.log(SecurityEvent.IP_RATE_LIMITED, email, ipAddress);
            throw new CustomerException("Too many requests from your location. Please try again later.");
        }

        // 2. Account-level lockout check
        if (rateLimitService.isAccountLocked(email)) {
            long seconds = rateLimitService.getRemainingLockoutSeconds(email);
            auditService.log(SecurityEvent.ACCOUNT_LOCKED, email, ipAddress);
            throw new CustomerException(
                "Account temporarily locked due to too many failed attempts. " +
                "Please try again in " + formatSeconds(seconds) + "."
            );
        }

        // 3. Find customer
        Customer customer = customerRepository.findByEmail(email).orElse(null);

        if (customer == null || !passwordEncoder.matches(password, customer.getPasswordHash())) {
            // Record failure even if email doesn't exist — prevents user enumeration
            rateLimitService.recordFailedLogin(email);
            auditService.log(SecurityEvent.LOGIN_FAILURE, email, ipAddress, "Invalid credentials");
            throw new CustomerException("Invalid email or password.");
        }

        // 4. Status check
        if (!CustomerStatus.ACTIVE.equals(customer.getStatus())) {
            auditService.log(SecurityEvent.LOGIN_FAILURE, email, ipAddress,
                "Login attempt on non-active account: " + customer.getStatus());
            throw new CustomerException(switch (customer.getStatus()) {
                case PENDING      -> "Your email is not verified yet. Please check your inbox.";
                case OTP_VERIFIED -> "Your account is awaiting admin approval.";
                case REJECTED     -> "Your account application was not approved. Please contact support.";
                case SUSPENDED    -> "Your account has been suspended. Please contact support.";
                default           -> "Account is not active.";
            });
        }

        // 5. Successful login — reset failure counter, update last login
        rateLimitService.resetFailedLogins(email);
        customer.setLastLoginAt(LocalDateTime.now());
        customerRepository.save(customer);

        // 6. Issue tokens
        String accessToken = jwtService.generateAccessToken(customer);
        String rawRefresh  = generateSecureToken();

        refreshTokenRepository.save(RefreshToken.builder()
                .customer(customer)
                .tokenHash(hashToken(rawRefresh))
                .deviceInfo(deviceInfo)
                .ipAddress(ipAddress)
                .expiresAt(LocalDateTime.now().plusDays(REFRESH_EXPIRY_DAYS))
                .revoked(false)
                .build());

        auditService.log(SecurityEvent.LOGIN_SUCCESS, email, ipAddress);
        return accessToken;
    }

    // ── Logout ────────────────────────────────────────────────

    @Transactional
    public void logout(Long customerId, String accessToken, String ipAddress) {
        // Revoke access token immediately — added to Redis denylist
        if (accessToken != null) {
            jwtService.revokeToken(accessToken);
        }
        // Revoke all refresh tokens for this customer
        refreshTokenRepository.revokeAllForCustomer(customerId);

        customerRepository.findById(customerId).ifPresent(c ->
            auditService.log(SecurityEvent.LOGOUT, c.getEmail(), ipAddress)
        );
    }

    // ── Admin ─────────────────────────────────────────────────

    @Transactional
    public void approveCustomer(Long customerId, String adminUsername) {
        Customer customer = findByIdOrThrow(customerId);
        if (!CustomerStatus.OTP_VERIFIED.equals(customer.getStatus()))
            throw new CustomerException("Customer is not in OTP_VERIFIED state.");

        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setApprovedAt(LocalDateTime.now());
        customer.setApprovedBy(adminUsername);
        customerRepository.save(customer);

        logAudit(customer, adminUsername, AdminAuditLog.AdminAction.APPROVED, null);
        emailService.sendApprovalEmail(customer);
        auditService.log(SecurityEvent.ADMIN_APPROVE, customer.getEmail(), "admin:" + adminUsername);
    }

    @Transactional
    public void rejectCustomer(Long customerId, String adminUsername, String reason) {
        Customer customer = findByIdOrThrow(customerId);
        customer.setStatus(CustomerStatus.REJECTED);
        customerRepository.save(customer);
        logAudit(customer, adminUsername, AdminAuditLog.AdminAction.REJECTED, reason);
        emailService.sendRejectionEmail(customer, reason);
        auditService.log(SecurityEvent.ADMIN_REJECT, customer.getEmail(), "admin:" + adminUsername, reason);
    }

    @Transactional
    public void suspendCustomer(Long customerId, String adminUsername, String reason) {
        Customer customer = findByIdOrThrow(customerId);
        if (!CustomerStatus.ACTIVE.equals(customer.getStatus()))
            throw new CustomerException("Only ACTIVE customers can be suspended.");
        customer.setStatus(CustomerStatus.SUSPENDED);
        customerRepository.save(customer);
        refreshTokenRepository.revokeAllForCustomer(customerId);
        logAudit(customer, adminUsername, AdminAuditLog.AdminAction.SUSPENDED, reason);
        auditService.log(SecurityEvent.ADMIN_SUSPEND, customer.getEmail(), "admin:" + adminUsername, reason);
    }

    // ── Helpers ───────────────────────────────────────────────

    private void issueAndSendOtp(Customer customer, OtpType type) {
        String code = String.format("%06d", new Random().nextInt(999999));
        otpRepository.save(OtpVerification.builder()
                .customer(customer).otpCode(code).otpType(type)
                .expiresAt(LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES))
                .used(false).attempts(0).build());
        emailService.sendOtpEmail(customer, code);
    }

    private void logAudit(Customer c, String admin, AdminAuditLog.AdminAction action, String reason) {
        auditLogRepository.save(AdminAuditLog.builder()
                .customer(c).adminUsername(admin).action(action).reason(reason).build());
    }

    private Customer findByEmailOrThrow(String email) {
        return customerRepository.findByEmail(email)
                .orElseThrow(() -> new CustomerException("No account found with this email."));
    }

    private Customer findByIdOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new CustomerException("Customer not found: " + id));
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Token hashing failed", e);
        }
    }

    private String formatSeconds(long seconds) {
        if (seconds >= 3600) return (seconds / 3600) + " hour(s)";
        if (seconds >= 60)   return (seconds / 60)   + " minute(s)";
        return seconds + " second(s)";
    }
}

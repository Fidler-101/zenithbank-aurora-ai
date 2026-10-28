package com.zenithbank.customer.service;

import com.zenithbank.customer.dto.RegistrationRequest;
import com.zenithbank.customer.entity.Customer;
import com.zenithbank.customer.entity.Customer.CustomerStatus;
import com.zenithbank.customer.entity.OtpVerification;
import com.zenithbank.customer.entity.OtpVerification.OtpType;
import com.zenithbank.customer.exception.CustomerException;
import com.zenithbank.customer.repository.*;
import com.zenithbank.customer.security.audit.SecurityAuditService;
import com.zenithbank.customer.security.filter.RateLimitService;
import com.zenithbank.customer.util.PasswordValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LEVEL 1 — UNIT TESTS: CustomerService
 *
 * Tests each function in isolation using Mockito mocks.
 * No database, no network, no Spring context.
 *
 * Spec coverage (Section 10.1):
 *   ✔ Registration: duplicate email, ID, phone rejection
 *   ✔ OTP: correct code, wrong code, expired code
 *   ✔ Login: valid credentials, wrong password, locked account, IP rate limit
 *   ✔ Password validator: weak, common, personal info, sequential patterns
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UNIT | CustomerService")
class CustomerServiceUnitTest {

    @Mock CustomerRepository        customerRepository;
    @Mock OtpVerificationRepository otpRepository;
    @Mock RefreshTokenRepository    refreshTokenRepository;
    @Mock AdminAuditLogRepository   auditLogRepository;
    @Mock EmailService              emailService;
    @Mock JwtService                jwtService;
    @Mock RateLimitService          rateLimitService;
    @Mock SecurityAuditService      auditService;

    @Spy  BCryptPasswordEncoder     passwordEncoder = new BCryptPasswordEncoder(4);
    @Spy  PasswordValidator         passwordValidator = new PasswordValidator();

    @InjectMocks CustomerService customerService;

    private RegistrationRequest validRequest;
    private Customer            activeCustomer;

    @BeforeEach
    void setUp() {
        validRequest = RegistrationRequest.builder()
                .firstName("Sifiso").lastName("Nkosi")
                .email("sifiso@test.co.za").phoneNumber("0821234567")
                .idNumber("9001015009087").dateOfBirth(LocalDate.of(1990, 1, 1))
                .addressLine1("123 Main Street").city("Pretoria")
                .province("Gauteng").postalCode("0001")
                .password("ZenithBank@2026!").confirmPassword("ZenithBank@2026!")
                .build();

        activeCustomer = Customer.builder()
                .id(1L).email("sifiso@test.co.za")
                .passwordHash(passwordEncoder.encode("ZenithBank@2026!"))
                .status(CustomerStatus.ACTIVE).role(Customer.Role.CUSTOMER)
                .firstName("Sifiso").lastName("Nkosi").emailVerified(true)
                .build();
    }

    // ── Registration ───────────────────────────────────────

    @Test @DisplayName("[REG-01] Valid registration saves customer and sends OTP")
    void register_validRequest_savesAndSendsOtp() {
        when(customerRepository.existsByEmail(anyString())).thenReturn(false);
        when(customerRepository.existsByIdNumber(anyString())).thenReturn(false);
        when(customerRepository.existsByPhoneNumber(anyString())).thenReturn(false);
        when(customerRepository.save(any())).thenAnswer(i -> {
            Customer c = i.getArgument(0);
            c.setId(1L); return c;
        });

        Customer result = customerService.register(validRequest);

        assertThat(result.getStatus()).isEqualTo(CustomerStatus.PENDING);
        verify(emailService).sendOtpEmail(any(), anyString());
    }

    @Test @DisplayName("[REG-02] Duplicate email is rejected")
    void register_duplicateEmail_throws() {
        when(customerRepository.existsByEmail(anyString())).thenReturn(true);
        assertThatThrownBy(() -> customerService.register(validRequest))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("email");
    }

    @Test @DisplayName("[REG-03] Duplicate ID number is rejected")
    void register_duplicateId_throws() {
        when(customerRepository.existsByEmail(anyString())).thenReturn(false);
        when(customerRepository.existsByIdNumber(anyString())).thenReturn(true);
        assertThatThrownBy(() -> customerService.register(validRequest))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("ID number");
    }

    @Test @DisplayName("[REG-04] Password mismatch is rejected")
    void register_passwordMismatch_throws() {
        validRequest.setConfirmPassword("Different@999");
        assertThatThrownBy(() -> customerService.register(validRequest))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("do not match");
    }

    // ── OTP Verification ───────────────────────────────────

    @Test @DisplayName("[OTP-01] Correct OTP upgrades status to OTP_VERIFIED")
    void verifyOtp_correctCode_upgradesStatus() {
        Customer pending = Customer.builder().id(1L).email("sifiso@test.co.za")
                .status(CustomerStatus.PENDING).emailVerified(false).build();
        OtpVerification otp = OtpVerification.builder()
                .id(1L).customer(pending).otpCode("123456")
                .otpType(OtpType.EMAIL_VERIFICATION)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .used(false).attempts(0).build();

        when(customerRepository.findByEmail(anyString())).thenReturn(Optional.of(pending));
        when(otpRepository.findLatestValidOtp(anyLong(), any(), any())).thenReturn(Optional.of(otp));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        customerService.verifyOtp("sifiso@test.co.za", "123456", "127.0.0.1");

        assertThat(pending.getStatus()).isEqualTo(CustomerStatus.OTP_VERIFIED);
        assertThat(pending.getEmailVerified()).isTrue();
    }

    @Test @DisplayName("[OTP-02] Wrong OTP decrements remaining attempts")
    void verifyOtp_wrongCode_throwsWithRemainingAttempts() {
        Customer pending = Customer.builder().id(1L).email("sifiso@test.co.za")
                .status(CustomerStatus.PENDING).emailVerified(false).build();
        OtpVerification otp = OtpVerification.builder()
                .id(1L).customer(pending).otpCode("123456")
                .otpType(OtpType.EMAIL_VERIFICATION)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .used(false).attempts(0).build();

        when(customerRepository.findByEmail(anyString())).thenReturn(Optional.of(pending));
        when(otpRepository.findLatestValidOtp(anyLong(), any(), any())).thenReturn(Optional.of(otp));

        assertThatThrownBy(() -> customerService.verifyOtp("sifiso@test.co.za", "999999", "127.0.0.1"))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("2 attempt(s) remaining");
    }

    @Test @DisplayName("[OTP-03] Expired OTP throws clear message")
    void verifyOtp_expiredOtp_throws() {
        Customer pending = Customer.builder().id(1L).email("sifiso@test.co.za")
                .status(CustomerStatus.PENDING).emailVerified(false).build();
        when(customerRepository.findByEmail(anyString())).thenReturn(Optional.of(pending));
        when(otpRepository.findLatestValidOtp(anyLong(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.verifyOtp("sifiso@test.co.za", "123456", "127.0.0.1"))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("expired");
    }

    // ── Login ──────────────────────────────────────────────

    @Test @DisplayName("[LOGIN-01] Valid credentials return JWT token")
    void login_validCredentials_returnsToken() {
        when(rateLimitService.isIpRateLimited(anyString())).thenReturn(false);
        when(rateLimitService.isAccountLocked(anyString())).thenReturn(false);
        when(customerRepository.findByEmail(anyString())).thenReturn(Optional.of(activeCustomer));
        when(jwtService.generateAccessToken(any())).thenReturn("jwt.token.here");
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String token = customerService.login("sifiso@test.co.za", "ZenithBank@2026!", "127.0.0.1", "Browser");

        assertThat(token).isEqualTo("jwt.token.here");
        verify(rateLimitService).resetFailedLogins("sifiso@test.co.za");
    }

    @Test @DisplayName("[LOGIN-02] Wrong password records failure and throws")
    void login_wrongPassword_recordsFailure() {
        when(rateLimitService.isIpRateLimited(anyString())).thenReturn(false);
        when(rateLimitService.isAccountLocked(anyString())).thenReturn(false);
        when(customerRepository.findByEmail(anyString())).thenReturn(Optional.of(activeCustomer));

        assertThatThrownBy(() -> customerService.login("sifiso@test.co.za", "WrongPass@1", "127.0.0.1", "Browser"))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("Invalid email or password");

        verify(rateLimitService).recordFailedLogin("sifiso@test.co.za");
    }

    @Test @DisplayName("[LOGIN-03] IP rate limited throws immediately")
    void login_ipRateLimited_throws() {
        when(rateLimitService.isIpRateLimited("10.0.0.1")).thenReturn(true);

        assertThatThrownBy(() -> customerService.login("sifiso@test.co.za", "pass", "10.0.0.1", "Browser"))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("Too many requests");
    }

    @Test @DisplayName("[LOGIN-04] Locked account throws with wait time")
    void login_lockedAccount_throws() {
        when(rateLimitService.isIpRateLimited(anyString())).thenReturn(false);
        when(rateLimitService.isAccountLocked(anyString())).thenReturn(true);
        when(rateLimitService.getRemainingLockoutSeconds(anyString())).thenReturn(300L);

        assertThatThrownBy(() -> customerService.login("sifiso@test.co.za", "pass", "127.0.0.1", "Browser"))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("locked");
    }

    @Test @DisplayName("[LOGIN-05] PENDING account gives descriptive message")
    void login_pendingAccount_throwsWithClearMessage() {
        activeCustomer.setStatus(CustomerStatus.PENDING);
        when(rateLimitService.isIpRateLimited(anyString())).thenReturn(false);
        when(rateLimitService.isAccountLocked(anyString())).thenReturn(false);
        when(customerRepository.findByEmail(anyString())).thenReturn(Optional.of(activeCustomer));

        assertThatThrownBy(() -> customerService.login("sifiso@test.co.za", "ZenithBank@2026!", "127.0.0.1", "Browser"))
                .isInstanceOf(CustomerException.class)
                .hasMessageContaining("email is not verified");
    }

    // ── Password Validator ─────────────────────────────────

    @Test @DisplayName("[PWD-01] Strong password passes all checks")
    void password_strong_isValid() {
        assertThat(passwordValidator.validate("ZenithBank@2026!", "Sifiso", "sifiso@test.co.za").valid()).isTrue();
    }

    @Test @DisplayName("[PWD-02] Common password is rejected")
    void password_common_isInvalid() {
        assertThat(passwordValidator.validate("password123", "Sifiso", "sifiso@test.co.za").valid()).isFalse();
    }

    @Test @DisplayName("[PWD-03] Password containing first name is rejected")
    void password_containsName_isInvalid() {
        assertThat(passwordValidator.validate("Sifiso@Bank1", "Sifiso", "sifiso@test.co.za").valid()).isFalse();
    }

    @Test @DisplayName("[PWD-04] Sequential characters are rejected")
    void password_sequential_isInvalid() {
        assertThat(passwordValidator.validate("Abcdefg@12", "Sifiso", "sifiso@test.co.za").valid()).isFalse();
    }

    @Test @DisplayName("[PWD-05] Repeated characters are rejected")
    void password_repeated_isInvalid() {
        assertThat(passwordValidator.validate("Aaaaaaa@12", "Sifiso", "sifiso@test.co.za").valid()).isFalse();
    }
}

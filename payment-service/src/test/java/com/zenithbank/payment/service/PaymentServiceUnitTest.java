package com.zenithbank.payment.service;

import com.zenithbank.payment.config.BankingServiceClient;
import com.zenithbank.payment.dto.AccountCheckDto;
import com.zenithbank.payment.dto.ApiResponse;
import com.zenithbank.payment.dto.PaymentRequest;
import com.zenithbank.payment.dto.PaymentResponse;
import com.zenithbank.payment.entity.Payment;
import com.zenithbank.payment.entity.Payment.PaymentFrequency;
import com.zenithbank.payment.entity.Payment.PaymentStatus;
import com.zenithbank.payment.exception.PaymentException;
import com.zenithbank.payment.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LEVEL 1 — UNIT TESTS: PaymentService
 *
 * Spec coverage (Section 10.1 + FR-ZB-05):
 *   ✔ Payment date must not be in the past
 *   ✔ Branch code must be valid
 *   ✔ Account must belong to the customer
 *   ✔ Account must be ACTIVE
 *   ✔ Balance must be sufficient (including overdraft boundary)
 *   ✔ Recurring payments compute correct nextRunDate
 *   ✔ Future-dated payments stay PENDING
 *   ✔ Cancellation rules enforced
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UNIT | PaymentService")
class PaymentServiceUnitTest {

    @Mock PaymentRepository         paymentRepository;
    @Mock BranchRepository          branchRepository;
    @Mock PaymentAuditLogRepository auditLogRepository;
    @Mock BankingServiceClient      bankingClient;

    @InjectMocks PaymentService paymentService;

    private final Long   CUSTOMER_ID = 1L;
    private final UUID   ACCOUNT_ID  = UUID.randomUUID();
    private final String BRANCH_CODE = "250655";
    private final String TO_ACCOUNT  = "12345678901";

    private PaymentRequest  validRequest;
    private AccountCheckDto healthyAccount;

    @BeforeEach
    void setUp() {
        validRequest = PaymentRequest.builder()
                .fromAccountId(ACCOUNT_ID).toAccountNo(TO_ACCOUNT)
                .branchCode(BRANCH_CODE).amount(new BigDecimal("1000.00"))
                .reference("Test payment").paymentDate(LocalDate.now().plusDays(1))
                .isRecurring(false).frequency(Payment.PaymentFrequency.DAILY).build();

        healthyAccount = new AccountCheckDto();
        healthyAccount.setId(ACCOUNT_ID);
        healthyAccount.setAccountNumber("400000000001");
        healthyAccount.setAvailableBalance(new BigDecimal("24850.00"));
        healthyAccount.setOverdraftLimit(new BigDecimal("5000.00"));
        healthyAccount.setStatus("ACTIVE");
    }

    private Payment savedPayment(PaymentStatus status, LocalDate date) {
        return Payment.builder().id(UUID.randomUUID()).customerId(CUSTOMER_ID)
                .fromAccountId(ACCOUNT_ID).fromAccountNo("400000000001")
                .toAccountNo(TO_ACCOUNT).branchCode(BRANCH_CODE)
                .amount(validRequest.getAmount()).paymentDate(date)
                .frequency(Payment.PaymentFrequency.ONCE).isRecurring(false)
                .status(status).build();
    }

    // ── Validation Rules (FR-ZB-05) ────────────────────────

    @Test @DisplayName("[PAY-01] Past payment date is rejected")
    void create_pastDate_throws() {
        validRequest.setPaymentDate(LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> paymentService.createPayment(validRequest, CUSTOMER_ID))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("past");
    }

    @Test @DisplayName("[PAY-02] Invalid branch code is rejected")
    void create_invalidBranch_throws() {
        when(branchRepository.existsByBranchCode(BRANCH_CODE)).thenReturn(false);
        assertThatThrownBy(() -> paymentService.createPayment(validRequest, CUSTOMER_ID))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("branch code");
    }

    @Test @DisplayName("[PAY-03] Inactive account is rejected")
    void create_inactiveAccount_throws() {
        healthyAccount.setStatus("FROZEN");
        when(branchRepository.existsByBranchCode(BRANCH_CODE)).thenReturn(true);
        when(bankingClient.getAccountBalance(ACCOUNT_ID, CUSTOMER_ID)).thenReturn(ApiResponse.ok(healthyAccount));
        assertThatThrownBy(() -> paymentService.createPayment(validRequest, CUSTOMER_ID))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("not active");
    }

    @Test @DisplayName("[PAY-04] Insufficient balance is rejected")
    void create_insufficientBalance_throws() {
        healthyAccount.setAvailableBalance(new BigDecimal("500.00"));
        healthyAccount.setOverdraftLimit(BigDecimal.ZERO);
        validRequest.setAmount(new BigDecimal("1000.00"));
        when(branchRepository.existsByBranchCode(BRANCH_CODE)).thenReturn(true);
        when(bankingClient.getAccountBalance(ACCOUNT_ID, CUSTOMER_ID)).thenReturn(ApiResponse.ok(healthyAccount));
        assertThatThrownBy(() -> paymentService.createPayment(validRequest, CUSTOMER_ID))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test @DisplayName("[PAY-05] Amount exactly at balance + overdraft limit is accepted")
    void create_exactlyAtLimit_succeeds() {
        validRequest.setAmount(new BigDecimal("29850.00")); // 24850 + 5000
        when(branchRepository.existsByBranchCode(BRANCH_CODE)).thenReturn(true);
        when(bankingClient.getAccountBalance(ACCOUNT_ID, CUSTOMER_ID)).thenReturn(ApiResponse.ok(healthyAccount));
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0); p.setId(UUID.randomUUID()); return p;
        });
        assertThatNoException().isThrownBy(() -> paymentService.createPayment(validRequest, CUSTOMER_ID));
    }

    @Test @DisplayName("[PAY-06] One cent over limit is rejected")
    void create_oneCentOverLimit_throws() {
        validRequest.setAmount(new BigDecimal("29850.01"));
        when(branchRepository.existsByBranchCode(BRANCH_CODE)).thenReturn(true);
        when(bankingClient.getAccountBalance(ACCOUNT_ID, CUSTOMER_ID)).thenReturn(ApiResponse.ok(healthyAccount));
        assertThatThrownBy(() -> paymentService.createPayment(validRequest, CUSTOMER_ID))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test @DisplayName("[PAY-07] Future-dated payment stays PENDING (no debit)")
    void create_futureDate_staysPending() {
        validRequest.setPaymentDate(LocalDate.now().plusDays(5));
        when(branchRepository.existsByBranchCode(BRANCH_CODE)).thenReturn(true);
        when(bankingClient.getAccountBalance(ACCOUNT_ID, CUSTOMER_ID)).thenReturn(ApiResponse.ok(healthyAccount));
        when(paymentRepository.save(any())).thenAnswer(i -> savedPayment(PaymentStatus.PENDING, LocalDate.now().plusDays(5)));

        PaymentResponse response = paymentService.createPayment(validRequest, CUSTOMER_ID);

        assertThat(response.getStatus()).isEqualTo("PENDING");
        verify(bankingClient, never()).recordDebit(any(), any(), any(), any(), any(), any(), any());
    }

    // ── Recurring Payments ─────────────────────────────────

    @Test @DisplayName("[REC-01] DAILY frequency sets nextRunDate to tomorrow")
    void recurring_daily_nextRunDateIsTomorrow() {
        Payment p = Payment.builder().isRecurring(true)
                .frequency(Payment.PaymentFrequency.DAILY).paymentDate(LocalDate.now()).build();
        assertThat(p.computeNextRunDate()).isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test @DisplayName("[REC-02] WEEKLY frequency sets nextRunDate to next week")
    void recurring_weekly_nextRunDateIsNextWeek() {
        Payment p = Payment.builder().isRecurring(true)
                .frequency(Payment.PaymentFrequency.WEEKLY).paymentDate(LocalDate.now()).build();
        assertThat(p.computeNextRunDate()).isEqualTo(LocalDate.now().plusWeeks(1));
    }

    @Test @DisplayName("[REC-03] MONTHLY frequency sets nextRunDate to next month")
    void recurring_monthly_nextRunDateIsNextMonth() {
        Payment p = Payment.builder().isRecurring(true)
                .frequency(Payment.PaymentFrequency.MONTHLY).paymentDate(LocalDate.now()).build();
        assertThat(p.computeNextRunDate()).isEqualTo(LocalDate.now().plusMonths(1));
    }

    @Test @DisplayName("[REC-04] ONCE frequency returns null nextRunDate")
    void recurring_once_nextRunDateIsNull() {
        Payment p = Payment.builder().isRecurring(false)
                .frequency(Payment.PaymentFrequency.ONCE).paymentDate(LocalDate.now()).build();
        assertThat(p.computeNextRunDate()).isNull();
    }

    // ── Cancellation ───────────────────────────────────────

    @Test @DisplayName("[CANCEL-01] Pending future-dated payment can be cancelled")
    void cancel_pendingFuture_succeeds() {
        UUID id = UUID.randomUUID();
        Payment p = savedPayment(PaymentStatus.PENDING, LocalDate.now().plusDays(3));
        p.setId(id);
        when(paymentRepository.findByIdAndCustomerId(id, CUSTOMER_ID)).thenReturn(Optional.of(p));
        when(paymentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentResponse result = paymentService.cancelPayment(id, CUSTOMER_ID);
        assertThat(result.getStatus()).isEqualTo("CANCELLED");
    }

    @Test @DisplayName("[CANCEL-02] Completed payment cannot be cancelled")
    void cancel_completed_throws() {
        UUID id = UUID.randomUUID();
        Payment p = savedPayment(PaymentStatus.COMPLETED, LocalDate.now().minusDays(1));
        p.setId(id);
        when(paymentRepository.findByIdAndCustomerId(id, CUSTOMER_ID)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> paymentService.cancelPayment(id, CUSTOMER_ID))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("cannot be cancelled");
    }

    @Test @DisplayName("[CANCEL-03] Payment not belonging to customer throws")
    void cancel_wrongCustomer_throws() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findByIdAndCustomerId(id, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.cancelPayment(id, 99L))
                .isInstanceOf(PaymentException.class)
                .hasMessageContaining("not found");
    }
}

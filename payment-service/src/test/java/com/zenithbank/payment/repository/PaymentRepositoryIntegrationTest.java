package com.zenithbank.payment.repository;

import com.zenithbank.payment.entity.Branch;
import com.zenithbank.payment.entity.Payment;
import com.zenithbank.payment.entity.Payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * LEVEL 2 — INTEGRATION TESTS: PaymentRepository
 *
 * Runs against an H2 in-memory database (PostgreSQL mode).
 * Tests actual JPA query logic — not mocked.
 *
 * Spec coverage (Section 10.1):
 *   ✔ Repository Layer: actual database queries against test DB
 *   ✔ Ownership isolation between customers
 *   ✔ Scheduling queries: future-dated and recurring payments
 *   ✔ Due-payment pickup for the scheduler
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("INTEGRATION | PaymentRepository")
class PaymentRepositoryIntegrationTest {

    @Autowired PaymentRepository paymentRepository;
    @Autowired BranchRepository  branchRepository;

    private final Long CUSTOMER_A = 1L;
    private final Long CUSTOMER_B = 2L;
    private final UUID ACCOUNT_A  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        branchRepository.save(Branch.builder()
                .branchCode("250655").branchName("Standard Bank HO")
                .bankName("Standard Bank").region("Gauteng").build());
    }

    private Payment payment(Long customer, LocalDate date, PaymentStatus status, boolean recurring) {
        return Payment.builder()
                .customerId(customer).fromAccountId(ACCOUNT_A)
                .fromAccountNo("400000000001").toAccountNo("12345678901")
                .branchCode("250655").amount(new BigDecimal("500.00"))
                .paymentDate(date)
                .frequency(recurring ? PaymentFrequency.MONTHLY : PaymentFrequency.ONCE)
                .isRecurring(recurring)
                .nextRunDate(recurring ? LocalDate.now().plusMonths(1) : null)
                .status(status).build();
    }

    // ── Ownership Isolation ────────────────────────────────

    @Test @DisplayName("[INT-01] Customer can retrieve their own payment")
    void findByIdAndCustomerId_correctOwner_returnsPayment() {
        Payment saved = paymentRepository.save(payment(CUSTOMER_A, LocalDate.now(), PaymentStatus.PENDING, false));
        Optional<Payment> result = paymentRepository.findByIdAndCustomerId(saved.getId(), CUSTOMER_A);
        assertThat(result).isPresent();
        assertThat(result.get().getCustomerId()).isEqualTo(CUSTOMER_A);
    }

    @Test @DisplayName("[INT-02] Customer cannot retrieve another customer's payment")
    void findByIdAndCustomerId_wrongOwner_returnsEmpty() {
        Payment saved = paymentRepository.save(payment(CUSTOMER_A, LocalDate.now(), PaymentStatus.PENDING, false));
        assertThat(paymentRepository.findByIdAndCustomerId(saved.getId(), CUSTOMER_B)).isEmpty();
    }

    // ── Scheduled Payments ─────────────────────────────────

    @Test @DisplayName("[INT-03] Future-dated pending payments appear in scheduled list")
    void findScheduled_futureDate_returnsPayment() {
        paymentRepository.save(payment(CUSTOMER_A, LocalDate.now().plusDays(5), PaymentStatus.PENDING, false));
        paymentRepository.save(payment(CUSTOMER_A, LocalDate.now().minusDays(1), PaymentStatus.COMPLETED, false));

        List<Payment> result = paymentRepository.findScheduledByCustomer(CUSTOMER_A, LocalDate.now());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPaymentDate()).isAfter(LocalDate.now());
    }

    @Test @DisplayName("[INT-04] Recurring pending payments appear in scheduled list")
    void findScheduled_recurring_returnsPayment() {
        paymentRepository.save(payment(CUSTOMER_A, LocalDate.now().plusDays(1), PaymentStatus.PENDING, true));
        List<Payment> result = paymentRepository.findScheduledByCustomer(CUSTOMER_A, LocalDate.now());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getIsRecurring()).isTrue();
    }

    @Test @DisplayName("[INT-05] Scheduled list does not include other customer's payments")
    void findScheduled_isolation_excludesOtherCustomers() {
        paymentRepository.save(payment(CUSTOMER_B, LocalDate.now().plusDays(3), PaymentStatus.PENDING, false));
        assertThat(paymentRepository.findScheduledByCustomer(CUSTOMER_A, LocalDate.now())).isEmpty();
    }

    // ── Due Payments (Scheduler) ───────────────────────────

    @Test @DisplayName("[INT-06] Payment due today is picked up by scheduler")
    void findDue_today_returnsPayment() {
        paymentRepository.save(payment(CUSTOMER_A, LocalDate.now(), PaymentStatus.PENDING, false));
        paymentRepository.save(payment(CUSTOMER_A, LocalDate.now().plusDays(3), PaymentStatus.PENDING, false));

        List<Payment> due = paymentRepository.findDueForProcessing(LocalDate.now());
        assertThat(due).hasSize(1);
        assertThat(due.get(0).getPaymentDate()).isEqualTo(LocalDate.now());
    }

    @Test @DisplayName("[INT-07] Overdue past-dated payment is picked up by scheduler")
    void findDue_overdue_returnsPayment() {
        paymentRepository.save(payment(CUSTOMER_A, LocalDate.now().minusDays(2), PaymentStatus.PENDING, false));
        assertThat(paymentRepository.findDueForProcessing(LocalDate.now())).hasSize(1);
    }

    @Test @DisplayName("[INT-08] Cancelled payment is not picked up by scheduler")
    void findDue_cancelled_isExcluded() {
        paymentRepository.save(payment(CUSTOMER_A, LocalDate.now(), PaymentStatus.CANCELLED, false));
        assertThat(paymentRepository.findDueForProcessing(LocalDate.now())).isEmpty();
    }

    @Test @DisplayName("[INT-09] Completed payment is not picked up by scheduler")
    void findDue_completed_isExcluded() {
        paymentRepository.save(payment(CUSTOMER_A, LocalDate.now().minusDays(1), PaymentStatus.COMPLETED, false));
        assertThat(paymentRepository.findDueForProcessing(LocalDate.now())).isEmpty();
    }
}

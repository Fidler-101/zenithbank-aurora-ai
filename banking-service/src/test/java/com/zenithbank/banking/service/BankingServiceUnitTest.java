package com.zenithbank.banking.service;

import com.zenithbank.banking.dto.AsyncJobDto;
import com.zenithbank.banking.dto.DashboardDto;
import com.zenithbank.banking.dto.TransactionPageDto;
import com.zenithbank.banking.entity.Account;
import com.zenithbank.banking.entity.Account.AccountStatus;
import com.zenithbank.banking.entity.Account.AccountType;
import com.zenithbank.banking.entity.AsyncTransactionJob;
import com.zenithbank.banking.exception.BankingException;
import com.zenithbank.banking.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LEVEL 1 — UNIT TESTS: BankingService
 *
 * Spec coverage (Section 10.1):
 *   ✔ Balance calculation: credit, debit, overdraft boundary
 *   ✔ Dashboard: totals, account masking
 *   ✔ Transaction history: sync/async threshold at exactly 5 days
 *   ✔ BOLA: account access denied for wrong customer
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UNIT | BankingService")
class BankingServiceUnitTest {

    @Mock AccountRepository             accountRepository;
    @Mock TransactionRepository         transactionRepository;
    @Mock AsyncTransactionJobRepository jobRepository;

    @InjectMocks AccountService     accountService;
    TransactionService              transactionService;

    private final Long CUSTOMER_ID = 1L;
    private final UUID ACCOUNT_ID  = UUID.randomUUID();
    private Account testAccount;

    @BeforeEach
    void setUp() {
        testAccount = Account.builder()
                .id(ACCOUNT_ID).customerId(CUSTOMER_ID)
                .accountNumber("400000000001").accountType(AccountType.CHEQUE)
                .accountName("ZenithBank Cheque")
                .balance(new BigDecimal("24850.00"))
                .availableBalance(new BigDecimal("24850.00"))
                .overdraftLimit(new BigDecimal("5000.00"))
                .currency("ZAR").status(AccountStatus.ACTIVE).build();

        transactionService = new TransactionService(transactionRepository, jobRepository, accountService, new FraudRiskCalculator());
    }

    // ── Balance Calculation ────────────────────────────────

    @Test @DisplayName("[BAL-01] Debit reduces balance and available balance correctly")
    void debit_reducesBalances() {
        testAccount.debit(new BigDecimal("1000.00"));
        assertThat(testAccount.getBalance()).isEqualByComparingTo("23850.00");
        assertThat(testAccount.getAvailableBalance()).isEqualByComparingTo("23850.00");
    }

    @Test @DisplayName("[BAL-02] Credit increases balance and available balance correctly")
    void credit_increasesBalances() {
        testAccount.credit(new BigDecimal("5000.00"));
        assertThat(testAccount.getBalance()).isEqualByComparingTo("29850.00");
        assertThat(testAccount.getAvailableBalance()).isEqualByComparingTo("29850.00");
    }

    @Test @DisplayName("[BAL-03] Sufficient funds within overdraft limit returns true")
    void hasSufficientFunds_withinOverdraft_returnsTrue() {
        // balance 24850 + overdraft 5000 = 29850 max
        assertThat(testAccount.hasSufficientFunds(new BigDecimal("29850.00"))).isTrue();
    }

    @Test @DisplayName("[BAL-04] Amount one cent over limit returns false")
    void hasSufficientFunds_overLimit_returnsFalse() {
        assertThat(testAccount.hasSufficientFunds(new BigDecimal("29850.01"))).isFalse();
    }

    // ── Dashboard ──────────────────────────────────────────

    @Test @DisplayName("[DASH-01] Dashboard sums balances across all accounts")
    void getDashboard_multipleAccounts_sumsTotals() {
        Account savings = Account.builder()
                .id(UUID.randomUUID()).customerId(CUSTOMER_ID)
                .accountNumber("400000000002").accountType(AccountType.SAVINGS)
                .balance(new BigDecimal("10000.00")).availableBalance(new BigDecimal("10000.00"))
                .overdraftLimit(BigDecimal.ZERO).status(AccountStatus.ACTIVE).build();

        when(accountRepository.findByCustomerIdAndStatusOrderByCreatedAtAsc(CUSTOMER_ID, AccountStatus.ACTIVE))
                .thenReturn(List.of(testAccount, savings));
        when(transactionRepository.findTop5ByAccountIdOrderByInitiatedAtDesc(any()))
                .thenReturn(List.of());

        DashboardDto result = accountService.getDashboard(CUSTOMER_ID);

        assertThat(result.getTotalBalance()).isEqualByComparingTo("34850.00");
        assertThat(result.getAccountCount()).isEqualTo(2);
    }

    @Test @DisplayName("[DASH-02] Account number is masked in dashboard response")
    void getDashboard_accountNumber_isMasked() {
        when(accountRepository.findByCustomerIdAndStatusOrderByCreatedAtAsc(CUSTOMER_ID, AccountStatus.ACTIVE))
                .thenReturn(List.of(testAccount));
        when(transactionRepository.findTop5ByAccountIdOrderByInitiatedAtDesc(any()))
                .thenReturn(List.of());

        DashboardDto result = accountService.getDashboard(CUSTOMER_ID);
        String masked = result.getAccounts().get(0).getAccountNumber();

        assertThat(masked).contains("****");
        assertThat(masked).doesNotContain("400000000001");
    }

    // ── Sync / Async Threshold ─────────────────────────────

    @Test @DisplayName("[TXN-01] Date range under 5 days returns synchronous result")
    void history_under5Days_isSync() {
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionRepository.findByAccountIdAndDateRange(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Object result = transactionService.getTransactionHistory(
            ACCOUNT_ID, CUSTOMER_ID, LocalDate.now().minusDays(3), LocalDate.now(), 0, 20);

        assertThat(result).isInstanceOf(TransactionPageDto.class);
        verify(jobRepository, never()).save(any());
    }

    @Test @DisplayName("[TXN-02] Date range of exactly 5 days triggers async")
    void history_exactly5Days_isAsync() {
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(testAccount));
        AsyncTransactionJob job = AsyncTransactionJob.builder()
                .id(UUID.randomUUID()).accountId(ACCOUNT_ID).customerId(CUSTOMER_ID)
                .status(AsyncTransactionJob.JobStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusHours(24)).build();
        when(jobRepository.save(any())).thenReturn(job);

        Object result = transactionService.getTransactionHistory(
            ACCOUNT_ID, CUSTOMER_ID, LocalDate.now().minusDays(5), LocalDate.now(), 0, 20);

        assertThat(result).isInstanceOf(AsyncJobDto.class);
    }

    @Test @DisplayName("[TXN-03] Date range of exactly 4 days returns synchronous result")
    void history_exactly4Days_isSync() {
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(testAccount));
        when(transactionRepository.findByAccountIdAndDateRange(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Object result = transactionService.getTransactionHistory(
            ACCOUNT_ID, CUSTOMER_ID, LocalDate.now().minusDays(4), LocalDate.now(), 0, 20);

        assertThat(result).isInstanceOf(TransactionPageDto.class);
    }

    @Test @DisplayName("[TXN-04] Date range over 5 days triggers async")
    void history_over5Days_isAsync() {
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(testAccount));
        AsyncTransactionJob job = AsyncTransactionJob.builder()
                .id(UUID.randomUUID()).accountId(ACCOUNT_ID).customerId(CUSTOMER_ID)
                .status(AsyncTransactionJob.JobStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusHours(24)).build();
        when(jobRepository.save(any())).thenReturn(job);

        Object result = transactionService.getTransactionHistory(
            ACCOUNT_ID, CUSTOMER_ID, LocalDate.now().minusDays(30), LocalDate.now(), 0, 20);

        assertThat(result).isInstanceOf(AsyncJobDto.class);
    }

    // ── BOLA (Broken Object Level Authorisation) ───────────

    @Test @DisplayName("[BOLA-01] Account owned by customer is accessible")
    void getAccount_ownedByCustomer_succeeds() {
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(testAccount));
        assertThatNoException().isThrownBy(
            () -> accountService.getOwnedAccountOrThrow(ACCOUNT_ID, CUSTOMER_ID));
    }

    @Test @DisplayName("[BOLA-02] Account owned by different customer is denied")
    void getAccount_differentCustomer_throwsBankingException() {
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, 99L))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> accountService.getOwnedAccountOrThrow(ACCOUNT_ID, 99L))
                .isInstanceOf(BankingException.class)
                .hasMessageContaining("not found");
    }

    @Test @DisplayName("[BOLA-03] Frozen account is denied even if customer owns it")
    void getAccount_frozenAccount_throwsBankingException() {
        testAccount.setStatus(AccountStatus.FROZEN);
        when(accountRepository.findByIdAndCustomerId(ACCOUNT_ID, CUSTOMER_ID))
                .thenReturn(Optional.of(testAccount));
        assertThatThrownBy(() -> accountService.getOwnedAccountOrThrow(ACCOUNT_ID, CUSTOMER_ID))
                .isInstanceOf(BankingException.class);
    }
}

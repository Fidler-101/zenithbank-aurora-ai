package com.zenithbank.banking.service;

import com.zenithbank.banking.dto.AccountSummaryDto;
import com.zenithbank.banking.dto.DashboardDto;
import com.zenithbank.banking.dto.TransactionDto;
import com.zenithbank.banking.entity.Account;
import com.zenithbank.banking.entity.Account.AccountStatus;
import com.zenithbank.banking.exception.BankingException;
import com.zenithbank.banking.repository.AccountRepository;
import com.zenithbank.banking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {
    private static final Logger log = Logger.getLogger(AccountService.class.getName());


    private final AccountRepository     accountRepository;
    private final TransactionRepository transactionRepository;

    // ── Dashboard — all accounts with last 5 transactions each ──

    @Transactional(readOnly = true)
    public DashboardDto getDashboard(Long customerId) {

        List<Account> accounts = accountRepository
                .findByCustomerIdAndStatusOrderByCreatedAtAsc(customerId, AccountStatus.ACTIVE);

        List<AccountSummaryDto> summaries = accounts.stream()
                .map(account -> {
                    List<TransactionDto> recent = transactionRepository
                            .findTop5ByAccountIdOrderByInitiatedAtDesc(account.getId())
                            .stream()
                            .map(TransactionDto::from)
                            .toList();
                    return AccountSummaryDto.from(account, recent);
                })
                .toList();

        BigDecimal totalBalance   = accounts.stream()
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalAvailable = accounts.stream()
                .map(Account::getAvailableBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.fine("Dashboard loaded for customer " + customerId + " — " + accounts.size() + " accounts");

        return DashboardDto.builder()
                .accounts(summaries)
                .totalBalance(totalBalance)
                .totalAvailable(totalAvailable)
                .accountCount(accounts.size())
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ── Live balance for a specific account ───────────────────

    @Transactional(readOnly = true)
    public AccountSummaryDto getBalance(UUID accountId, Long customerId) {
        Account account = getOwnedAccountOrThrow(accountId, customerId);
        return AccountSummaryDto.from(account, List.of());
    }

    // ── Ownership-checked account lookup ─────────────────────

    @Transactional(readOnly = true)
    public Account getOwnedAccountOrThrow(UUID accountId, Long customerId) {
        return accountRepository
                .findByIdAndCustomerId(accountId, customerId)
                .filter(Account::isActive)
                .orElseThrow(() -> new BankingException(
                    "Account not found or access denied."
                ));
    }

    // ── Debit an account (used by TransactionService.recordDebit) ──
    // Defense-in-depth funds check: payment-service already checked
    // available balance before calling this, but we check again here
    // since this account row is the single source of truth and a
    // race between two concurrent payments is otherwise possible.

    @Transactional
    public Account debitAccount(UUID accountId, Long customerId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BankingException("Debit amount must be greater than zero.");
        }
        Account account = getOwnedAccountOrThrow(accountId, customerId);

        if (!account.hasSufficientFunds(amount)) {
            throw new BankingException(
                "Insufficient funds. Available balance: R" +
                account.getAvailableBalance().toPlainString() + "."
            );
        }

        account.debit(amount);
        return accountRepository.save(account);
    }
}

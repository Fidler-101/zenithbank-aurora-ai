package com.zenithbank.banking.service;

import com.zenithbank.banking.dto.*;
import com.zenithbank.banking.entity.AsyncTransactionJob;
import com.zenithbank.banking.entity.Transaction;
import com.zenithbank.banking.exception.BankingException;
import com.zenithbank.banking.repository.AsyncTransactionJobRepository;
import com.zenithbank.banking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionService {
    private static final Logger log = Logger.getLogger(TransactionService.class.getName());


    private final TransactionRepository        transactionRepository;
    private final AsyncTransactionJobRepository jobRepository;
    private final AccountService               accountService;
    private final FraudRiskCalculator          fraudRiskCalculator;

    private static final int ASYNC_THRESHOLD_DAYS = 5;
    private static final int MAX_PAGE_SIZE        = 50;

    // ── Record a debit — the endpoint payment-service calls after ──
    // ── verifying funds and processing a payment (previously      ──
    // ── missing entirely; see Assignment 3 evaluation).            ──
    //
    // Fraud-risk score is computed ONCE here, from the balance this
    // debit actually observed, then stored immutably on the new
    // transaction row alongside everything else — never updated
    // afterwards (see Transaction.java).

    @Transactional
    public TransactionDto recordDebit(
            UUID accountId, Long customerId, BigDecimal amount,
            String description, String referenceNumber,
            String counterpartyAccount, String branchCode
    ) {
        var account = accountService.getOwnedAccountOrThrow(accountId, customerId);
        BigDecimal balanceBefore = account.getBalance();

        account = accountService.debitAccount(accountId, customerId, amount);
        BigDecimal balanceAfter = account.getBalance();

        var risk = fraudRiskCalculator.score(balanceBefore, amount, balanceAfter);
        if (risk.flagged()) {
            log.warning("Transaction " + referenceNumber + " on account " + accountId
                    + " flagged as high fraud-risk (score=" + risk.score() + ")");
        }

        Transaction txn = Transaction.builder()
                .account(account)
                .referenceNumber(referenceNumber)
                .type(Transaction.TransactionType.TRANSFER_OUT)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .description(description)
                .category(Transaction.TransactionCategory.TRANSFER)
                .counterpartyAccount(counterpartyAccount)
                .branchCode(branchCode)
                .status(Transaction.TransactionStatus.COMPLETED)
                .completedAt(LocalDateTime.now())
                .fraudRiskScore(risk.score())
                .isFlagged(risk.flagged())
                .build();

        txn = transactionRepository.save(txn);
        log.info("Recorded debit " + referenceNumber + " — R" + amount + " on account " + accountId
                + (risk.flagged() ? " [FLAGGED, score=" + risk.score() + "]" : ""));

        return TransactionDto.from(txn);
    }

    // ── Main entry point: sync or async based on date range ──

    @Transactional
    public Object getTransactionHistory(
            UUID accountId, Long customerId,
            LocalDate dateFrom, LocalDate dateTo,
            int page, int size
    ) {
        // BOLA check — verify ownership before touching data
        accountService.getOwnedAccountOrThrow(accountId, customerId);

        long daysDiff = ChronoUnit.DAYS.between(dateFrom, dateTo);

        if (daysDiff < ASYNC_THRESHOLD_DAYS) {
            return getSyncHistory(accountId, dateFrom, dateTo, page, size);
        } else {
            return submitAsyncJob(accountId, customerId, dateFrom, dateTo);
        }
    }

    // ── Sync — returns immediately (< 5 days) ────────────────

    @Transactional(readOnly = true)
    public TransactionPageDto getSyncHistory(
            UUID accountId, LocalDate dateFrom, LocalDate dateTo,
            int page, int size
    ) {
        size = Math.min(size, MAX_PAGE_SIZE);
        var pageable = PageRequest.of(page, size, Sort.by("initiatedAt").descending());

        Page<Transaction> result = transactionRepository.findByAccountIdAndDateRange(
            accountId,
            dateFrom.atStartOfDay(),
            dateTo.atTime(23, 59, 59),
            pageable
        );

        return TransactionPageDto.builder()
                .transactions(result.getContent().stream().map(TransactionDto::from).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }

    // ── Async — submits job, client polls (≥ 5 days) ─────────

    @Transactional
    public AsyncJobDto submitAsyncJob(
            UUID accountId, Long customerId,
            LocalDate dateFrom, LocalDate dateTo
    ) {
        AsyncTransactionJob job = AsyncTransactionJob.builder()
                .accountId(accountId)
                .customerId(customerId)
                .dateFrom(dateFrom)
                .dateTo(dateTo)
                .status(AsyncTransactionJob.JobStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .build();

        job = jobRepository.save(job);
        log.info("Async job " + job.getId() + " queued for account " + accountId + " (" + dateFrom + " → " + dateTo + ")");

        // Fire and forget — background thread picks this up
        processJobAsync(job.getId());

        return AsyncJobDto.builder()
                .jobId(job.getId())
                .status("PENDING")
                .message("Your report is being prepared. Poll /transactions/jobs/" + job.getId() + " for results.")
                .createdAt(job.getCreatedAt())
                .build();
    }

    // ── Poll job status ───────────────────────────────────────

    @Transactional(readOnly = true)
    public AsyncJobDto pollJob(UUID jobId, Long customerId) {
        AsyncTransactionJob job = jobRepository
                .findByIdAndCustomerId(jobId, customerId)
                .orElseThrow(() -> new BankingException("Job not found or access denied."));

        AsyncJobDto.AsyncJobDtoBuilder builder = AsyncJobDto.builder()
                .jobId(job.getId())
                .status(job.getStatus().name())
                .resultCount(job.getResultCount())
                .createdAt(job.getCreatedAt())
                .completedAt(job.getCompletedAt());

        if (job.getStatus() == AsyncTransactionJob.JobStatus.COMPLETED) {
            // Fetch results from DB now that the job is done
            List<TransactionDto> results = transactionRepository
                    .findAllByAccountIdAndDateRange(
                        job.getAccountId(),
                        job.getDateFrom().atStartOfDay(),
                        job.getDateTo().atTime(23, 59, 59)
                    )
                    .stream()
                    .map(TransactionDto::from)
                    .toList();

            builder.transactions(results)
                   .message("Report ready — " + results.size() + " transactions found.");

        } else if (job.getStatus() == AsyncTransactionJob.JobStatus.FAILED) {
            builder.message("Report generation failed. Please try again.");
        } else {
            builder.message("Report is still being prepared. Please check again shortly.");
        }

        return builder.build();
    }

    // ── Background job processor ──────────────────────────────

    @Async
    @Transactional
    public void processJobAsync(UUID jobId) {
        AsyncTransactionJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        try {
            job.markProcessing();
            jobRepository.save(job);

            // Small delay to simulate real async processing
            Thread.sleep(500);

            List<Transaction> results = transactionRepository.findAllByAccountIdAndDateRange(
                    job.getAccountId(),
                    job.getDateFrom().atStartOfDay(),
                    job.getDateTo().atTime(23, 59, 59)
            );

            job.markCompleted(results.size());
            jobRepository.save(job);
            log.info("Async job " + jobId + " completed — " + results.size() + " transactions found");

        } catch (Exception e) {
            job.markFailed(e.getMessage());
            jobRepository.save(job);
            log.severe("Async job " + jobId + " failed: " + e.getMessage());
        }
    }

    // ── Spending breakdown ────────────────────────────────────

    @Transactional(readOnly = true)
    public SpendingBreakdownDto getSpendingBreakdown(
            UUID accountId, Long customerId,
            LocalDate dateFrom, LocalDate dateTo
    ) {
        accountService.getOwnedAccountOrThrow(accountId, customerId);

        LocalDateTime from = dateFrom.atStartOfDay();
        LocalDateTime to   = dateTo.atTime(23, 59, 59);

        List<Object[]> rows = transactionRepository.sumSpendingByCategory(accountId, from, to);

        BigDecimal totalSpend = rows.stream()
                .map(r -> (BigDecimal) r[1])
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<SpendingBreakdownDto.CategorySpend> categories = rows.stream()
                .map(r -> {
                    BigDecimal amount = (BigDecimal) r[1];
                    double pct = totalSpend.compareTo(BigDecimal.ZERO) > 0
                            ? amount.divide(totalSpend, 4, RoundingMode.HALF_UP)
                                     .multiply(BigDecimal.valueOf(100))
                                     .doubleValue()
                            : 0.0;
                    return SpendingBreakdownDto.CategorySpend.builder()
                            .category(r[0].toString())
                            .amount(amount)
                            .percentage(pct)
                            .build();
                })
                .toList();

        BigDecimal totalCredits = transactionRepository.sumCreditsInPeriod(accountId, from, to);

        return SpendingBreakdownDto.builder()
                .categories(categories)
                .totalSpend(totalSpend)
                .totalCredits(totalCredits)
                .periodFrom(dateFrom.toString())
                .periodTo(dateTo.toString())
                .build();
    }
}

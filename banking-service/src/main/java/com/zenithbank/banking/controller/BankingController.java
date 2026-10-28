package com.zenithbank.banking.controller;

import com.zenithbank.banking.dto.*;
import com.zenithbank.banking.exception.BankingException;
import com.zenithbank.banking.service.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.logging.Logger;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Banking REST Controller
 *
 * All endpoints require the X-Customer-Id header, injected by
 * the API Gateway's JwtAuthFilter after token validation.
 * Never trust a customerId from the request body or query params.
 *
 * Base path: /api/banking/** (after gateway strips /api/banking)
 */
@RestController
@RequiredArgsConstructor
public class BankingController {
    private static final Logger log = Logger.getLogger(BankingController.class.getName());


    private final AccountService         accountService;
    private final TransactionService     transactionService;
    private final FinancialHealthService              healthService;
    private final com.zenithbank.banking.config.FeatureSwitchService featureSwitch;

    // ── Dashboard ─────────────────────────────────────────────

    /**
     * GET /dashboard
     * Returns all active accounts with last 5 transactions each.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<DashboardDto>> getDashboard(
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        DashboardDto dashboard = accountService.getDashboard(customerId);
        return ResponseEntity.ok(ApiResponse.ok(dashboard));
    }

    // ── Account Balance ───────────────────────────────────────

    /**
     * GET /accounts/{accountId}/balance
     * Returns live balance for a specific account.
     * BOLA check: account must belong to the requesting customer.
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<ApiResponse<AccountSummaryDto>> getBalance(
            @PathVariable UUID accountId,
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        AccountSummaryDto balance = accountService.getBalance(accountId, customerId);
        return ResponseEntity.ok(ApiResponse.ok(balance));
    }

    // ── Transaction History ───────────────────────────────────

    /**
     * GET /accounts/{accountId}/transactions
     *
     * Date range < 5 days  → synchronous TransactionPageDto
     * Date range ≥ 5 days  → async AsyncJobDto with jobId to poll
     *
     * Default range: last 7 days if no params supplied.
     */
    @GetMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<ApiResponse<?>> getTransactions(
            @PathVariable UUID accountId,
            @RequestHeader("X-Customer-Id") Long customerId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")   @Min(0)   int page,
            @RequestParam(defaultValue = "20")  @Min(1) @Max(50) int size
    ) {
        LocalDate dateFrom = from != null ? from : LocalDate.now().minusDays(7);
        LocalDate dateTo   = to   != null ? to   : LocalDate.now();

        if (dateFrom.isAfter(dateTo)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("'from' date must be before 'to' date."));
        }

        Object result = transactionService.getTransactionHistory(
            accountId, customerId, dateFrom, dateTo, page, size
        );
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // ── Poll Async Job ────────────────────────────────────────

    /**
     * GET /transactions/jobs/{jobId}
     * Polls the status of an async transaction history job.
     */
    @GetMapping("/transactions/jobs/{jobId}")
    public ResponseEntity<ApiResponse<AsyncJobDto>> pollJob(
            @PathVariable UUID jobId,
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        AsyncJobDto job = transactionService.pollJob(jobId, customerId);
        return ResponseEntity.ok(ApiResponse.ok(job));
    }

    // ── Spending Breakdown ────────────────────────────────────

    /**
     * GET /accounts/{accountId}/spending
     * Returns spending by category for the given period.
     * Default: current calendar month.
     */
    @GetMapping("/accounts/{accountId}/spending")
    public ResponseEntity<ApiResponse<SpendingBreakdownDto>> getSpending(
            @PathVariable UUID accountId,
            @RequestHeader("X-Customer-Id") Long customerId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        LocalDate dateFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate dateTo   = to   != null ? to   : LocalDate.now();

        SpendingBreakdownDto breakdown = transactionService.getSpendingBreakdown(
            accountId, customerId, dateFrom, dateTo
        );
        return ResponseEntity.ok(ApiResponse.ok(breakdown));
    }

    // ── AI Financial Health Score ─────────────────────────────

    /**
     * GET /accounts/{accountId}/health-score
     * Computes a 0–100 financial health score with AI insights.
     */
    @GetMapping("/accounts/{accountId}/health-score")
    public ResponseEntity<ApiResponse<HealthScoreDto>> getHealthScore(
            @PathVariable UUID accountId,
            @RequestHeader("X-Customer-Id") Long customerId
    ) {
        if (!featureSwitch.isEnabled("health-score")) {
            return ResponseEntity.status(503)
                    .body(ApiResponse.error(
                        "AI Financial Health Score is temporarily unavailable. Please try again later."));
        }
        HealthScoreDto score = healthService.computeHealthScore(accountId, customerId);
        return ResponseEntity.ok(ApiResponse.ok(score));
    }

    // ── Record Debit (called by payment-service via Feign) ────
    // Previously missing entirely — every payment failed the moment
    // PaymentService.processPayment() called this. See Assignment 3
    // evaluation for how this was found (payments were silently
    // failing; the Feign client had no matching endpoint to call).

    @PostMapping("/accounts/{accountId}/debit")
    public ResponseEntity<ApiResponse<TransactionDto>> recordDebit(
            @PathVariable UUID accountId,
            @RequestHeader("X-Customer-Id") Long customerId,
            @RequestParam java.math.BigDecimal amount,
            @RequestParam String description,
            @RequestParam String referenceNumber,
            @RequestParam(required = false) String counterpartyAccount,
            @RequestParam(required = false) String branchCode
    ) {
        TransactionDto txn = transactionService.recordDebit(
            accountId, customerId, amount, description,
            referenceNumber, counterpartyAccount, branchCode
        );
        return ResponseEntity.ok(ApiResponse.ok(txn));
    }

    // ── Error handler ─────────────────────────────────────────

    @ExceptionHandler(BankingException.class)
    public ResponseEntity<ApiResponse<Void>> handleBankingException(BankingException e) {
        log.warning("Banking exception: " + e.getMessage());
        return ResponseEntity.status(403).body(ApiResponse.error(e.getMessage()));
    }
}

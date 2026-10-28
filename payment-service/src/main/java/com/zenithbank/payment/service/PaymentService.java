package com.zenithbank.payment.service;

import com.zenithbank.payment.config.BankingServiceClient;
import com.zenithbank.payment.dto.AccountCheckDto;
import com.zenithbank.payment.dto.PaymentRequest;
import com.zenithbank.payment.dto.PaymentResponse;
import com.zenithbank.payment.entity.Payment;
import com.zenithbank.payment.entity.Payment.PaymentStatus;
import com.zenithbank.payment.entity.PaymentAuditLog;
import com.zenithbank.payment.exception.PaymentException;
import com.zenithbank.payment.repository.BranchRepository;
import com.zenithbank.payment.repository.PaymentAuditLogRepository;
import com.zenithbank.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.logging.Logger;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {
    private static final Logger log = Logger.getLogger(PaymentService.class.getName());


    private final PaymentRepository       paymentRepository;
    private final BranchRepository        branchRepository;
    private final PaymentAuditLogRepository auditLogRepository;
    private final BankingServiceClient    bankingClient;

    // ── Create Payment ────────────────────────────────────────

    @Transactional
    public PaymentResponse createPayment(PaymentRequest req, Long customerId) {

        // 0. Basic server-side input validation (the UI check alone can be bypassed)
        if (req.getAmount() == null || req.getAmount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new PaymentException("Amount must be greater than zero.");
        }
        if (req.getToAccountNo() == null || req.getToAccountNo().isBlank()) {
            throw new PaymentException("Beneficiary account number is required.");
        }
        if (req.getReference() != null && req.getReference().length() > 100) {
            throw new PaymentException("Reference must be 100 characters or fewer.");
        }
        if (req.getPaymentDate() == null) {
            throw new PaymentException("Payment date is required.");
        }

        // 1. Payment date must not be in the past
        if (req.getPaymentDate().isBefore(LocalDate.now())) {
            throw new PaymentException("Payment date cannot be in the past.");
        }

        // 2. Validate branch code exists
        if (!branchRepository.existsByBranchCode(req.getBranchCode())) {
            throw new PaymentException("Invalid branch code: " + req.getBranchCode() + ". Please check the recipient's bank details.");
        }

        // 3. Verify account ownership + check balance via banking-service
        AccountCheckDto account;
        try {
            account = bankingClient.getAccountBalance(req.getFromAccountId(), customerId).getData();
        } catch (Exception e) {
            throw new PaymentException("Could not verify account. Please try again.");
        }

        if (!"ACTIVE".equals(account.getStatus())) {
            throw new PaymentException("The selected account is not active.");
        }

        // 4. Sufficient funds check (balance + overdraft limit)
        var available = account.getAvailableBalance().add(account.getOverdraftLimit());
        if (available.compareTo(req.getAmount()) < 0) {
            throw new PaymentException(
                "Insufficient funds. Available balance: R" +
                account.getAvailableBalance().toPlainString() + "."
            );
        }

        // 5. Persist payment record
        Payment payment = Payment.builder()
                .customerId(customerId)
                .fromAccountId(req.getFromAccountId())
                .fromAccountNo(account.getAccountNumber())
                .toAccountNo(req.getToAccountNo())
                .branchCode(req.getBranchCode())
                .amount(req.getAmount())
                .reference(req.getReference())
                .paymentDate(req.getPaymentDate())
                .frequency(req.getFrequency() != null ? req.getFrequency() : Payment.PaymentFrequency.ONCE)
                .isRecurring(Boolean.TRUE.equals(req.getIsRecurring()))
                .status(PaymentStatus.PENDING)
                .build();

        if (payment.getIsRecurring()) {
            payment.setNextRunDate(payment.computeNextRunDate());
        }

        payment = paymentRepository.save(payment);
        auditLog(payment.getId(), null, PaymentStatus.PENDING, "Payment created");

        log.info("Payment " + payment.getId() + " created by customer " + customerId + " — R" + payment.getAmount() + " to " + payment.getToAccountNo() + " on " + payment.getPaymentDate());

        // 6. If payment date is today, process immediately
        if (!payment.getPaymentDate().isAfter(LocalDate.now())) {
            payment = processPayment(payment);
        }

        return PaymentResponse.from(payment);
    }

    // ── Process Payment (called by scheduler or immediately) ──

    @Transactional
    public Payment processPayment(Payment payment) {

        payment.setStatus(PaymentStatus.PROCESSING);
        paymentRepository.save(payment);
        auditLog(payment.getId(), PaymentStatus.PENDING, PaymentStatus.PROCESSING, "Processing started");

        try {
            String ref = "ZB" + System.currentTimeMillis();

            // Debit the customer's account via banking-service
            bankingClient.recordDebit(
                payment.getFromAccountId(),
                payment.getCustomerId(),
                payment.getAmount(),
                buildDescription(payment),
                ref,
                payment.getToAccountNo(),
                payment.getBranchCode()
            );

            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setProcessedAt(LocalDateTime.now());
            payment.setProofGenerated(false); // generated on demand

            // Advance recurring payment schedule
            if (payment.getIsRecurring()) {
                payment.setNextRunDate(payment.computeNextRunDate());
            }

            paymentRepository.save(payment);
            auditLog(payment.getId(), PaymentStatus.PROCESSING, PaymentStatus.COMPLETED, null);

            log.info("Payment " + payment.getId() + " completed — R" + payment.getAmount() + " to " + payment.getToAccountNo());

        } catch (Exception e) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(e.getMessage());
            paymentRepository.save(payment);
            auditLog(payment.getId(), PaymentStatus.PROCESSING, PaymentStatus.FAILED, e.getMessage());
            log.severe("Payment " + payment.getId() + " failed: " + e.getMessage());
        }

        return payment;
    }

    // ── Cancel Payment ────────────────────────────────────────

    @Transactional
    public PaymentResponse cancelPayment(UUID paymentId, Long customerId) {

        Payment payment = paymentRepository.findByIdAndCustomerId(paymentId, customerId)
                .orElseThrow(() -> new PaymentException("Payment not found or access denied."));

        if (!payment.isCancellable()) {
            throw new PaymentException(
                "This payment cannot be cancelled. Only pending future-dated or recurring payments can be cancelled."
            );
        }

        PaymentStatus prev = payment.getStatus();
        payment.setStatus(PaymentStatus.CANCELLED);
        payment.setCancelledAt(LocalDateTime.now());
        paymentRepository.save(payment);

        auditLog(payment.getId(), prev, PaymentStatus.CANCELLED, "Cancelled by customer");
        log.info("Payment " + paymentId + " cancelled by customer " + customerId);

        return PaymentResponse.from(payment);
    }

    // ── Get Scheduled Payments ────────────────────────────────

    @Transactional(readOnly = true)
    public List<PaymentResponse> getScheduledPayments(Long customerId) {
        return paymentRepository
                .findScheduledByCustomer(customerId, LocalDate.now())
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    // ── Get Single Payment ────────────────────────────────────

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId, Long customerId) {
        Payment payment = paymentRepository.findByIdAndCustomerId(paymentId, customerId)
                .orElseThrow(() -> new PaymentException("Payment not found or access denied."));
        return PaymentResponse.from(payment);
    }

    // ── Get Due Payments (used by scheduler) ─────────────────

    @Transactional(readOnly = true)
    public List<Payment> getDuePayments() {
        return paymentRepository.findDueForProcessing(LocalDate.now());
    }

    // ── Helpers ───────────────────────────────────────────────

    private String buildDescription(Payment p) {
        String ref = p.getReference() != null && !p.getReference().isBlank()
                ? " Ref: " + p.getReference()
                : "";
        return "Payment to " + p.getToAccountNo() + ref;
    }

    private void auditLog(UUID paymentId, PaymentStatus from, PaymentStatus to, String reason) {
        auditLogRepository.save(PaymentAuditLog.builder()
                .paymentId(paymentId)
                .fromStatus(from != null ? from.name() : null)
                .toStatus(to.name())
                .reason(reason)
                .build());
    }
}

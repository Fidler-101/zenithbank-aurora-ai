package com.zenithbank.txnstatus.service;

import com.zenithbank.txnstatus.config.BankingStatusClient;
import com.zenithbank.txnstatus.config.PaymentStatusClient;
import com.zenithbank.txnstatus.dto.PaymentDto;
import com.zenithbank.txnstatus.dto.TransactionDto;
import com.zenithbank.txnstatus.dto.TransactionStatusResponse;
import com.zenithbank.txnstatus.exception.StatusException;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

import java.util.UUID;

/**
 * Transaction Status Service
 *
 * A given ID could belong to:
 *   - A transaction record in banking-service (ledger entry)
 *   - A payment record in payment-service (outbound payment)
 *
 * Strategy: try banking-service first (most common case).
 * If not found (404), try payment-service.
 * If neither knows the ID, return 404 to the client.
 *
 * Ownership is enforced by both upstream services via X-Customer-Id.
 */
@Service
@RequiredArgsConstructor
public class TransactionStatusService {
    private static final Logger log = Logger.getLogger(TransactionStatusService.class.getName());


    private final BankingStatusClient bankingClient;
    private final PaymentStatusClient paymentClient;

    public TransactionStatusResponse getStatus(UUID id, Long customerId) {

        // 1. Try banking-service first
        try {
            TransactionDto txn = bankingClient.getTransaction(id, customerId).getData();
            log.fine("Status resolved from banking-service for id " + id);
            return TransactionStatusResponse.builder()
                    .id(txn.getId())
                    .type("TRANSACTION")
                    .status(txn.getStatus())
                    .amount(txn.getAmount())
                    .description(txn.getDescription())
                    .toAccount(txn.getCounterpartyAccount())
                    .initiatedAt(txn.getInitiatedAt())
                    .completedAt(txn.getCompletedAt())
                    .build();

        } catch (FeignException.NotFound | FeignException.Forbidden ignored) {
            // Not a transaction — try payment-service
        } catch (FeignException e) {
            log.warning("banking-service error for id " + id + ": " + e.getMessage());
        }

        // 2. Try payment-service
        try {
            PaymentDto payment = paymentClient.getPayment(id, customerId).getData();
            log.fine("Status resolved from payment-service for id " + id);
            return TransactionStatusResponse.builder()
                    .id(payment.getId())
                    .type("PAYMENT")
                    .status(payment.getStatus())
                    .amount(payment.getAmount())
                    .description(payment.getReference() != null
                            ? "Payment — Ref: " + payment.getReference()
                            : "Payment to " + payment.getToAccountNo())
                    .fromAccount(payment.getFromAccountNo())
                    .toAccount(payment.getToAccountNo())
                    .initiatedAt(payment.getCreatedAt())
                    .completedAt(payment.getProcessedAt())
                    .failureReason(payment.getFailureReason())
                    .build();

        } catch (FeignException.NotFound | FeignException.Forbidden ignored) {
            // Not found in either service
        } catch (FeignException e) {
            log.warning("payment-service error for id " + id + ": " + e.getMessage());
        }

        throw new StatusException("No transaction or payment found with ID: " + id);
    }
}

package com.zenithbank.txnstatus.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Unified status response returned to the client.
 * Populated from either banking-service (type=TRANSACTION)
 * or payment-service (type=PAYMENT).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TransactionStatusResponse {

    private UUID          id;
    private String        type;          // TRANSACTION or PAYMENT
    private String        status;        // PENDING | PROCESSING | COMPLETED | FAILED | CANCELLED
    private BigDecimal    amount;
    private String        description;
    private String        fromAccount;
    private String        toAccount;
    private LocalDateTime initiatedAt;
    private LocalDateTime completedAt;
    private String        failureReason;
}

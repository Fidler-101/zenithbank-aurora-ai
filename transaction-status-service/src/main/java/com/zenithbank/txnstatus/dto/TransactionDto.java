package com.zenithbank.txnstatus.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Feign response shape — matches what banking-service returns
 * for GET /transactions/{transactionId}.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class TransactionDto {

    private UUID          id;
    private String        type;
    private String        status;
    private BigDecimal    amount;
    private String        description;
    private String        counterpartyAccount;
    private LocalDateTime initiatedAt;
    private LocalDateTime completedAt;
}

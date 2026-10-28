package com.zenithbank.txnstatus.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Feign response shape — matches what payment-service returns
 * for GET /payments/{paymentId}.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PaymentDto {

    private UUID          id;
    private String        status;
    private BigDecimal    amount;
    private String        reference;
    private String        fromAccountNo;
    private String        toAccountNo;
    private String        failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}

package com.zenithbank.payment.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Feign response shape — matches what banking-service returns
 * for GET /accounts/{accountId}/balance.
 * Used by PaymentService to verify balance before processing.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class AccountCheckDto {

    private UUID       id;
    private String     accountNumber;
    private BigDecimal availableBalance;
    private BigDecimal overdraftLimit;
    private String     status;
}

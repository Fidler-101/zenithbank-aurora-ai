package com.zenithbank.banking.dto;

import com.zenithbank.banking.entity.Transaction;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TransactionDto {

    private UUID          id;
    private String        referenceNumber;
    private String        type;
    private BigDecimal    amount;
    private BigDecimal    balanceAfter;
    private String        description;
    private String        category;
    private String        counterpartyName;
    private String        status;
    private LocalDateTime initiatedAt;
    private boolean       credit;
    private BigDecimal    fraudRiskScore;
    private boolean       flagged;

    public static TransactionDto from(Transaction t) {
        return TransactionDto.builder()
                .id(t.getId())
                .referenceNumber(t.getReferenceNumber())
                .type(t.getType().name())
                .amount(t.getAmount())
                .balanceAfter(t.getBalanceAfter())
                .description(t.getDescription())
                .category(t.getCategory().name())
                .counterpartyName(t.getCounterpartyName())
                .status(t.getStatus().name())
                .initiatedAt(t.getInitiatedAt())
                .credit(t.isCredit())
                .fraudRiskScore(t.getFraudRiskScore())
                .flagged(t.isFlagged())
                .build();
    }
}

package com.zenithbank.payment.dto;

import com.zenithbank.payment.entity.Payment;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentResponse {

    private UUID          id;
    private String        fromAccountNo;
    private String        toAccountNo;
    private String        branchCode;
    private BigDecimal    amount;
    private String        reference;
    private LocalDate     paymentDate;
    private String        frequency;
    private Boolean       isRecurring;
    private LocalDate     nextRunDate;
    private String        status;
    private UUID          transactionId;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;

    public static PaymentResponse from(Payment p) {
        return PaymentResponse.builder()
                .id(p.getId())
                .fromAccountNo(p.getFromAccountNo())
                .toAccountNo(p.getToAccountNo())
                .branchCode(p.getBranchCode())
                .amount(p.getAmount())
                .reference(p.getReference())
                .paymentDate(p.getPaymentDate())
                .frequency(p.getFrequency().name())
                .isRecurring(p.getIsRecurring())
                .nextRunDate(p.getNextRunDate())
                .status(p.getStatus().name())
                .transactionId(p.getTransactionId())
                .createdAt(p.getCreatedAt())
                .processedAt(p.getProcessedAt())
                .build();
    }
}

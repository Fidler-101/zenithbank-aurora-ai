package com.zenithbank.banking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DashboardDto {

    private List<AccountSummaryDto> accounts;
    private BigDecimal              totalBalance;
    private BigDecimal              totalAvailable;
    private int                     accountCount;
    private LocalDateTime           generatedAt;
}

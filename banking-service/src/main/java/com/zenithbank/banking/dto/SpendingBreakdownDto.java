package com.zenithbank.banking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SpendingBreakdownDto {

    private List<CategorySpend> categories;
    private BigDecimal          totalSpend;
    private BigDecimal          totalCredits;
    private String              periodFrom;
    private String              periodTo;

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CategorySpend {
        private String     category;
        private BigDecimal amount;
        private double     percentage;
    }
}

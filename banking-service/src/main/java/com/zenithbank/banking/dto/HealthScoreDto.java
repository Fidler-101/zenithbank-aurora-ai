package com.zenithbank.banking.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class HealthScoreDto {

    private int           score;       // 0–100
    private String        rating;      // POOR / FAIR / GOOD / EXCELLENT
    private String        summary;     // AI-generated plain-English summary
    private List<String>  insights;    // Bullet recommendations from AI
    private BigDecimal    savingsRate; // (credits - debits) / credits * 100
    private LocalDateTime generatedAt;
}

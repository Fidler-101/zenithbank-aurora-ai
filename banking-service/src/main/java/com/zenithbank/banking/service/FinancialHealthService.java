package com.zenithbank.banking.service;

import com.zenithbank.banking.dto.HealthScoreDto;
import com.zenithbank.banking.dto.SpendingBreakdownDto;
import com.zenithbank.banking.exception.BankingException;
import com.zenithbank.banking.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import java.util.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Financial Health Score Service
 *
 * Computes a 0–100 score from the last 30 days of transaction data,
 * then sends a structured prompt to the Groq API (LLaMA 3.1)
 * which returns a plain-English summary and bullet recommendations.
 *
 * Score breakdown:
 *   40 pts — savings rate     (credits - debits) / credits
 *   30 pts — balance stability (positive trend vs 30 days ago)
 *   30 pts — spending diversity (not all spend in one category)
 */
@Service
@RequiredArgsConstructor
public class FinancialHealthService {
    private static final Logger log = Logger.getLogger(FinancialHealthService.class.getName());


    private final AccountService        accountService;
    private final TransactionRepository transactionRepository;
    private final TransactionService    transactionService;
    private final RestTemplate          restTemplate;

    @Value("${app.groq.api-key:}")
    private String groqApiKey;

    @Value("${app.groq.api-url:https://api.groq.com/openai/v1/chat/completions}")
    private String groqApiUrl;

    @Value("${app.groq.model:llama-3.1-8b-instant}")
    private String groqModel;

    @Transactional(readOnly = true)
    public HealthScoreDto computeHealthScore(UUID accountId, Long customerId) {

        accountService.getOwnedAccountOrThrow(accountId, customerId);

        LocalDate today    = LocalDate.now();
        LocalDate from30   = today.minusDays(30);
        LocalDateTime from = from30.atStartOfDay();
        LocalDateTime to   = today.atTime(23, 59, 59);

        BigDecimal credits = transactionRepository.sumCreditsInPeriod(accountId, from, to);
        BigDecimal debits  = transactionRepository.sumDebitsInPeriod(accountId, from, to);

        // ── 1. Savings rate component (0–40 pts) ─────────────
        int savingsPoints = 0;
        BigDecimal savingsRate = BigDecimal.ZERO;

        if (credits.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal surplus = credits.subtract(debits);
            savingsRate = surplus.divide(credits, 4, RoundingMode.HALF_UP)
                                 .multiply(BigDecimal.valueOf(100));

            // 0% savings = 0 pts, 30%+ savings = 40 pts
            double rate = savingsRate.doubleValue();
            savingsPoints = (int) Math.min(40, Math.max(0, rate / 30.0 * 40));
        }

        // ── 2. Spending diversity component (0–30 pts) ────────
        SpendingBreakdownDto breakdown = transactionService.getSpendingBreakdown(
            accountId, customerId, from30, today
        );

        int diversityPoints = 0;
        if (!breakdown.getCategories().isEmpty()) {
            // Low diversity = one category dominates (bad)
            // High diversity = spread across many (good)
            double maxPct = breakdown.getCategories().stream()
                    .mapToDouble(SpendingBreakdownDto.CategorySpend::getPercentage)
                    .max().orElse(100.0);
            // maxPct = 100% → 0 pts, maxPct = 30% → 30 pts
            diversityPoints = (int) Math.min(30, Math.max(0, (100.0 - maxPct) / 70.0 * 30));
        }

        // ── 3. Balance stability component (0–30 pts) ─────────
        // Simple proxy: if net flow is positive this month, award full points
        int stabilityPoints = credits.compareTo(debits) >= 0 ? 30 : 15;

        int totalScore = savingsPoints + diversityPoints + stabilityPoints;

        String rating = totalScore >= 85 ? "EXCELLENT"
                      : totalScore >= 65 ? "GOOD"
                      : totalScore >= 40 ? "FAIR"
                      : "POOR";

        // ── 4. AI summary and insights via Groq ───────────────
        String[] aiOutput = fetchAiInsights(
            totalScore, rating, credits, debits, savingsRate,
            breakdown.getCategories()
        );

        return HealthScoreDto.builder()
                .score(totalScore)
                .rating(rating)
                .summary(aiOutput[0])
                .insights(List.of(aiOutput).subList(1, aiOutput.length))
                .savingsRate(savingsRate.setScale(1, RoundingMode.HALF_UP))
                .generatedAt(LocalDateTime.now())
                .build();
    }

    // ── Groq API call ─────────────────────────────────────────

    private String[] fetchAiInsights(
            int score, String rating,
            BigDecimal credits, BigDecimal debits, BigDecimal savingsRate,
            List<SpendingBreakdownDto.CategorySpend> categories
    ) {
        if (groqApiKey == null || groqApiKey.isBlank()) {
            log.warning("Groq API key not configured — returning fallback insights");
            return fallbackInsights(score, rating, savingsRate);
        }

        String topCategories = categories.stream()
                .limit(3)
                .map(c -> c.getCategory() + " (" + String.format("%.0f", c.getPercentage()) + "%)")
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        String prompt = String.format("""
            You are a South African financial advisor. A customer has a financial health score of %d/100 (%s).
            Last 30 days: income R%s, spending R%s, savings rate %.1f%%.
            Top spending categories: %s.
            
            Respond with EXACTLY this format (no markdown, no extra text):
            SUMMARY: [One sentence plain-English summary of their financial health]
            INSIGHT: [First specific, actionable recommendation]
            INSIGHT: [Second specific, actionable recommendation]
            INSIGHT: [Third specific, actionable recommendation]
            """,
            score, rating,
            credits.setScale(0, RoundingMode.HALF_UP),
            debits.setScale(0, RoundingMode.HALF_UP),
            savingsRate.doubleValue(),
            topCategories
        );

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(groqApiKey);

            Map<String, Object> body = Map.of(
                "model",       groqModel,
                "messages",    List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens",  300,
                "temperature", 0.4
            );

            ResponseEntity<Map> response = restTemplate.exchange(
                groqApiUrl, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                var choices = (List<?>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    var message = (Map<?, ?>) ((Map<?, ?>) choices.get(0)).get("message");
                    String content = message.get("content").toString();
                    return parseAiResponse(content);
                }
            }

        } catch (Exception e) {
            log.severe("Groq API call failed: " + e.getMessage());
        }

        return fallbackInsights(score, rating, savingsRate);
    }

    private String[] parseAiResponse(String content) {
        List<String> parts = new java.util.ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.startsWith("SUMMARY:")) {
                parts.add(0, line.substring("SUMMARY:".length()).trim());
            } else if (line.startsWith("INSIGHT:")) {
                parts.add(line.substring("INSIGHT:".length()).trim());
            }
        }
        return parts.isEmpty()
            ? fallbackInsights(0, "UNKNOWN", BigDecimal.ZERO)
            : parts.toArray(new String[0]);
    }

    private String[] fallbackInsights(int score, String rating, BigDecimal savingsRate) {
        return new String[]{
            "Your financial health score is " + score + "/100 (" + rating + ") based on your last 30 days of activity.",
            "Aim to save at least 20% of your monthly income to build a solid financial buffer.",
            "Review your top spending categories and identify areas where you can reduce discretionary spending.",
            "Consider setting up an automatic transfer to your savings account on payday."
        };
    }
}

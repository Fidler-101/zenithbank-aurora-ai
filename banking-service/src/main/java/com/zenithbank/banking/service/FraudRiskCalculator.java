package com.zenithbank.banking.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Data-driven fraud-risk scoring for a single debit.
 *
 * Heuristic: the strongest signal available on a single real-time
 * debit (no batched/offline dataset here, unlike the PaySim exploration
 * in Assignment 2) is how much of the account's balance this one
 * transaction removes. A transaction that drains an account almost
 * entirely in one go is the same "account drained" fraud signature
 * validated against PaySim's labelled data during the earlier dataset
 * exploration (see fraud_pipeline.py) — this reuses that finding,
 * adapted to the fields actually available at write-time in this
 * system's immutable ledger (balanceBefore/amount/balanceAfter),
 * rather than porting the PaySim pseudocode verbatim.
 *
 * Score is computed ONCE per transaction, at creation time, and is
 * never recalculated — consistent with the append-only ledger design.
 *
 * NOTE: the 0.85 flag threshold and the >=85% recall / <=5% false-positive
 * target (AT-03) still need to be validated against a real labelled
 * benchmark once enough live transaction volume exists — see Assignment
 * 3 Q5.2. This implementation makes that measurement possible; it does
 * not itself constitute the measurement.
 */
@Component
public class FraudRiskCalculator {

    private static final BigDecimal DRAIN_THRESHOLD   = new BigDecimal("0.05");  // <=5% of balance left
    private static final BigDecimal MIN_MEANINGFUL_BALANCE = new BigDecimal("500");
    private static final BigDecimal FLAG_THRESHOLD    = new BigDecimal("0.85");
    private static final BigDecimal LOW_RISK_SCALE    = new BigDecimal("0.4");
    private static final BigDecimal LOW_RISK_CAP      = new BigDecimal("0.5");

    public record Result(BigDecimal score, boolean flagged) {}

    public Result score(BigDecimal balanceBefore, BigDecimal amount, BigDecimal balanceAfter) {
        if (balanceBefore == null || balanceBefore.compareTo(BigDecimal.ZERO) <= 0) {
            return new Result(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP), false);
        }

        BigDecimal drainRatio = amount.divide(balanceBefore, 4, RoundingMode.HALF_UP);
        boolean nearlyDrained = balanceAfter.compareTo(balanceBefore.multiply(DRAIN_THRESHOLD)) <= 0;
        boolean meaningfulBalance = balanceBefore.compareTo(MIN_MEANINGFUL_BALANCE) > 0;

        BigDecimal score;
        if (nearlyDrained && meaningfulBalance) {
            // High-risk: this single transaction removed almost the
            // entire balance. Score tracks how completely it drained
            // the account.
            score = drainRatio.min(BigDecimal.ONE).setScale(4, RoundingMode.HALF_UP);
        } else {
            // Low/medium risk: scaled down and capped so a normal
            // transaction can never cross the flag threshold alone.
            score = drainRatio.multiply(LOW_RISK_SCALE).min(LOW_RISK_CAP).setScale(4, RoundingMode.HALF_UP);
        }

        boolean flagged = score.compareTo(FLAG_THRESHOLD) >= 0;
        return new Result(score, flagged);
    }
}

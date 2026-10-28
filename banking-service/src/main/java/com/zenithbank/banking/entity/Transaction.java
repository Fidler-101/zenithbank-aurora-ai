package com.zenithbank.banking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Transaction entity — the immutable banking ledger.
 *
 * Rows are NEVER updated or deleted after creation.
 * Corrections are handled via REVERSAL transactions.
 *
 * balanceAfter is denormalised for fast dashboard rendering
 * (avoids summing the full ledger every time).
 */
@Entity
@Table(name = "transactions")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "reference_number", nullable = false, unique = true, length = 20)
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private TransactionCategory category = TransactionCategory.UNCATEGORISED;

    @Column(name = "counterparty_name", length = 150)
    private String counterpartyName;

    @Column(name = "counterparty_account", length = 12)
    private String counterpartyAccount;

    @Column(name = "branch_code", length = 6)
    private String branchCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.COMPLETED;

    @CreationTimestamp
    @Column(name = "initiated_at", nullable = false, updatable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    /**
     * Data-driven fraud-risk indicator. Computed ONCE, at creation
     * time (see TransactionService.recordDebit), from the balance
     * movement observed on this single debit — never recalculated
     * or updated afterwards, consistent with this table's immutable-
     * ledger design. 0.0000-1.0000; see FraudRiskCalculator for the
     * scoring logic and TC-09/AT-03 for how this is evaluated.
     */
    @Column(name = "fraud_risk_score", nullable = false, precision = 5, scale = 4)
    private java.math.BigDecimal fraudRiskScore = java.math.BigDecimal.ZERO;

    @Column(name = "is_flagged", nullable = false)
    private boolean isFlagged = false;

    // ── Helpers ──────────────────────────────────────────────

    public boolean isCredit() {
        return TransactionType.CREDIT.equals(type)
            || TransactionType.TRANSFER_IN.equals(type)
            || TransactionType.INTEREST.equals(type);
    }

    public enum TransactionType {
        CREDIT, DEBIT, TRANSFER_IN, TRANSFER_OUT, FEE, INTEREST, REVERSAL
    }

    public enum TransactionStatus {
        PENDING, PROCESSING, COMPLETED, FAILED, REVERSED
    }

    public enum TransactionCategory {
        SALARY, GROCERIES, UTILITIES, TRANSPORT, ENTERTAINMENT,
        HEALTHCARE, EDUCATION, INSURANCE, RENT, SAVINGS,
        TRANSFER, FEES, INTEREST, UNCATEGORISED, OTHER
    }
}

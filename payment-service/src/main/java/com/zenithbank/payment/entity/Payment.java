package com.zenithbank.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id",     nullable = false) private Long   customerId;
    @Column(name = "from_account_id", nullable = false) private UUID   fromAccountId;
    @Column(name = "from_account_no", nullable = false) private String fromAccountNo;
    @Column(name = "to_account_no",   nullable = false) private String toAccountNo;
    @Column(name = "branch_code",     nullable = false) private String branchCode;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "reference", length = 100)
    private String reference;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 10)
    private PaymentFrequency frequency = PaymentFrequency.ONCE;

    @Builder.Default
    @Column(name = "is_recurring", nullable = false)
    private Boolean isRecurring = false;

    @Column(name = "next_run_date")
    private LocalDate nextRunDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Builder.Default
    @Column(name = "proof_generated", nullable = false)
    private Boolean proofGenerated = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "processed_at") private LocalDateTime processedAt;
    @Column(name = "cancelled_at") private LocalDateTime cancelledAt;

    // ── Helpers ──────────────────────────────────────────────

    public boolean isCancellable() {
        return PaymentStatus.PENDING.equals(this.status)
            && (this.paymentDate.isAfter(LocalDate.now()) || this.isRecurring);
    }

    public boolean isScheduledForFuture() {
        return this.paymentDate.isAfter(LocalDate.now());
    }

    public LocalDate computeNextRunDate() {
        if (!this.isRecurring || this.paymentDate == null) return null;
        return switch (this.frequency) {
            case DAILY   -> LocalDate.now().plusDays(1);
            case WEEKLY  -> LocalDate.now().plusWeeks(1);
            case MONTHLY -> LocalDate.now().plusMonths(1);
            default      -> null;
        };
    }

    public enum PaymentFrequency { ONCE, DAILY, WEEKLY, MONTHLY }

    public enum PaymentStatus { PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED }
}

package com.zenithbank.banking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "async_transaction_jobs")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AsyncTransactionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public void markProcessing() { this.status = JobStatus.PROCESSING; }

    public void markCompleted(int count) {
        this.status      = JobStatus.COMPLETED;
        this.resultCount = count;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.status       = JobStatus.FAILED;
        this.errorMessage = error;
        this.completedAt  = LocalDateTime.now();
    }

    public enum JobStatus { PENDING, PROCESSING, COMPLETED, FAILED }
}

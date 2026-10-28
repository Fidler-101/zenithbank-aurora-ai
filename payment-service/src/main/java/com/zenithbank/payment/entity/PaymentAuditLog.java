package com.zenithbank.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_audit_log")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class PaymentAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "from_status", length = 15)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 15)
    private String toStatus;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;
}

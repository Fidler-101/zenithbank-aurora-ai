package com.zenithbank.customer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin_audit_log")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class AdminAuditLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "admin_username", nullable = false, length = 100)
    private String adminUsername;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 50)
    private AdminAction action;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @CreationTimestamp
    @Column(name = "action_at", nullable = false, updatable = false)
    private LocalDateTime actionAt;

    public enum AdminAction { APPROVED, REJECTED, SUSPENDED, REINSTATED, REVIEWED }
}

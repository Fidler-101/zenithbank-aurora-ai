package com.zenithbank.banking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Account entity.
 *
 * Uses UUID primary key — never exposes sequential IDs externally.
 * customerId is a soft foreign key to customer-service; no DB join
 * crosses service boundaries.
 *
 * balance        = ledger balance (includes pending debits)
 * availableBalance = balance minus pending holds
 */
@Entity
@Table(name = "accounts")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "account_number", nullable = false, unique = true, length = 12)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType accountType;

    @Column(name = "account_name", nullable = false, length = 100)
    private String accountName;

    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @Column(name = "available_balance", nullable = false, precision = 15, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "ZAR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "interest_rate", precision = 5, scale = 4)
    private BigDecimal interestRate = BigDecimal.ZERO;

    @Column(name = "overdraft_limit", nullable = false, precision = 15, scale = 2)
    private BigDecimal overdraftLimit = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Helpers ──────────────────────────────────────────────

    public boolean isActive() {
        return AccountStatus.ACTIVE.equals(this.status);
    }

    public boolean hasSufficientFunds(BigDecimal amount) {
        return availableBalance.add(overdraftLimit).compareTo(amount) >= 0;
    }

    public void debit(BigDecimal amount) {
        this.balance          = this.balance.subtract(amount);
        this.availableBalance = this.availableBalance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance          = this.balance.add(amount);
        this.availableBalance = this.availableBalance.add(amount);
    }

    public enum AccountType  { CHEQUE, SAVINGS, BUSINESS, FIXED_DEPOSIT }
    public enum AccountStatus { ACTIVE, FROZEN, CLOSED, DORMANT }
}

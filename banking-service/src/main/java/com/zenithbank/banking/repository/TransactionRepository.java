package com.zenithbank.banking.repository;

import com.zenithbank.banking.entity.Transaction;
import com.zenithbank.banking.entity.Transaction.TransactionCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // ── Dashboard — last N transactions for an account ────────

    List<Transaction> findTop5ByAccountIdOrderByInitiatedAtDesc(UUID accountId);

    // ── Sync history — date range under 5 days ───────────────

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.id = :accountId
          AND t.initiatedAt BETWEEN :from AND :to
        ORDER BY t.initiatedAt DESC
    """)
    Page<Transaction> findByAccountIdAndDateRange(
        @Param("accountId") UUID accountId,
        @Param("from")      LocalDateTime from,
        @Param("to")        LocalDateTime to,
        Pageable pageable
    );

    // ── Async history — full date range (no pagination, runs in background) ──

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.account.id = :accountId
          AND t.initiatedAt BETWEEN :from AND :to
        ORDER BY t.initiatedAt DESC
    """)
    List<Transaction> findAllByAccountIdAndDateRange(
        @Param("accountId") UUID accountId,
        @Param("from")      LocalDateTime from,
        @Param("to")        LocalDateTime to
    );

    // ── Spending analytics — sum by category ─────────────────

    @Query("""
        SELECT t.category, SUM(t.amount) as total
        FROM Transaction t
        WHERE t.account.id = :accountId
          AND t.type IN ('DEBIT','TRANSFER_OUT','FEE')
          AND t.initiatedAt BETWEEN :from AND :to
          AND t.status = 'COMPLETED'
        GROUP BY t.category
        ORDER BY total DESC
    """)
    List<Object[]> sumSpendingByCategory(
        @Param("accountId") UUID accountId,
        @Param("from")      LocalDateTime from,
        @Param("to")        LocalDateTime to
    );

    // ── Total credits in period (for health score) ────────────

    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.account.id = :accountId
          AND t.type IN ('CREDIT','TRANSFER_IN','INTEREST')
          AND t.initiatedAt BETWEEN :from AND :to
          AND t.status = 'COMPLETED'
    """)
    BigDecimal sumCreditsInPeriod(
        @Param("accountId") UUID accountId,
        @Param("from")      LocalDateTime from,
        @Param("to")        LocalDateTime to
    );

    // ── Total debits in period (for health score) ─────────────

    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.account.id = :accountId
          AND t.type IN ('DEBIT','TRANSFER_OUT','FEE')
          AND t.initiatedAt BETWEEN :from AND :to
          AND t.status = 'COMPLETED'
    """)
    BigDecimal sumDebitsInPeriod(
        @Param("accountId") UUID accountId,
        @Param("from")      LocalDateTime from,
        @Param("to")        LocalDateTime to
    );

    // ── Transaction count for fraud screening ─────────────────

    @Query("""
        SELECT COUNT(t) FROM Transaction t
        WHERE t.account.id = :accountId
          AND t.initiatedAt >= :since
    """)
    long countRecentTransactions(
        @Param("accountId") UUID accountId,
        @Param("since")     LocalDateTime since
    );
}

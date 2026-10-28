package com.zenithbank.payment.repository;

import com.zenithbank.payment.entity.PaymentAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentAuditLogRepository extends JpaRepository<PaymentAuditLog, Long> {
    List<PaymentAuditLog> findByPaymentIdOrderByChangedAtDesc(UUID paymentId);
}

package com.zenithbank.payment.repository;

import com.zenithbank.payment.entity.Payment;
import com.zenithbank.payment.entity.Payment.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // Ownership check — always scope to customer
    @Query("SELECT p FROM Payment p WHERE p.id = :id AND p.customerId = :customerId")
    Optional<Payment> findByIdAndCustomerId(@Param("id") UUID id,
                                            @Param("customerId") Long customerId);

    // All scheduled (future-dated + recurring) for a customer
    @Query("""
        SELECT p FROM Payment p
        WHERE p.customerId = :customerId
          AND (p.paymentDate > :today OR p.isRecurring = true)
          AND p.status = 'PENDING'
        ORDER BY p.paymentDate ASC
    """)
    List<Payment> findScheduledByCustomer(@Param("customerId") Long customerId,
                                          @Param("today") LocalDate today);

    // Due today — picked up by the scheduler
    @Query("""
        SELECT p FROM Payment p
        WHERE p.status = 'PENDING'
          AND (p.paymentDate <= :today
            OR (p.isRecurring = true AND p.nextRunDate <= :today))
        ORDER BY p.createdAt ASC
    """)
    List<Payment> findDueForProcessing(@Param("today") LocalDate today);
}

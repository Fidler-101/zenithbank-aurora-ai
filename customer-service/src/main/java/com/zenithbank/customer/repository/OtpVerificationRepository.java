package com.zenithbank.customer.repository;

import com.zenithbank.customer.entity.OtpVerification;
import com.zenithbank.customer.entity.OtpVerification.OtpType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {

    @Query("""
        SELECT o FROM OtpVerification o
        WHERE o.customer.id = :customerId
          AND o.otpType = :type
          AND o.used = false
          AND o.expiresAt > :now
        ORDER BY o.createdAt DESC
        LIMIT 1
    """)
    Optional<OtpVerification> findLatestValidOtp(
        @Param("customerId") Long customerId,
        @Param("type") OtpType type,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("""
        UPDATE OtpVerification o SET o.used = true
        WHERE o.customer.id = :customerId AND o.otpType = :type AND o.used = false
    """)
    void invalidateAllForCustomer(@Param("customerId") Long customerId, @Param("type") OtpType type);
}

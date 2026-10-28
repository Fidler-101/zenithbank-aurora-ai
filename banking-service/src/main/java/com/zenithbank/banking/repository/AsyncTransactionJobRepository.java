package com.zenithbank.banking.repository;

import com.zenithbank.banking.entity.AsyncTransactionJob;
import com.zenithbank.banking.entity.AsyncTransactionJob.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AsyncTransactionJobRepository extends JpaRepository<AsyncTransactionJob, UUID> {

    // Ownership-checked lookup — customer can only poll their own jobs
    @Query("""
        SELECT j FROM AsyncTransactionJob j
        WHERE j.id = :jobId AND j.customerId = :customerId
    """)
    Optional<AsyncTransactionJob> findByIdAndCustomerId(
        @Param("jobId")      UUID jobId,
        @Param("customerId") Long customerId
    );

    // Pickup pending jobs for the async processor
    List<AsyncTransactionJob> findByStatusOrderByCreatedAtAsc(JobStatus status);

    // Cleanup expired jobs (scheduled task)
    @Modifying
    @Query("DELETE FROM AsyncTransactionJob j WHERE j.expiresAt < :now")
    void deleteExpiredJobs(@Param("now") LocalDateTime now);
}

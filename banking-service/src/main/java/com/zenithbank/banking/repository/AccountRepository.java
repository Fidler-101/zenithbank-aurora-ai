package com.zenithbank.banking.repository;

import com.zenithbank.banking.entity.Account;
import com.zenithbank.banking.entity.Account.AccountStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByCustomerIdAndStatusOrderByCreatedAtAsc(Long customerId, AccountStatus status);

    List<Account> findByCustomerIdOrderByCreatedAtAsc(Long customerId);

    Optional<Account> findByAccountNumber(String accountNumber);

    // Ownership check — always verify customer owns the account before exposing data
    @Query("SELECT a FROM Account a WHERE a.id = :id AND a.customerId = :customerId")
    Optional<Account> findByIdAndCustomerId(@Param("id") UUID id, @Param("customerId") Long customerId);

    boolean existsByAccountNumber(String accountNumber);
}

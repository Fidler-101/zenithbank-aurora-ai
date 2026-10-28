package com.zenithbank.customer.repository;

import com.zenithbank.customer.entity.Customer;
import com.zenithbank.customer.entity.Customer.CustomerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByIdNumber(String idNumber);
    boolean existsByPhoneNumber(String phoneNumber);
    List<Customer> findByStatusOrderByCreatedAtDesc(CustomerStatus status);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.status = :status")
    long countByStatus(@Param("status") CustomerStatus status);
}

package com.zenithbank.payment.config;

import com.zenithbank.payment.dto.AccountCheckDto;
import com.zenithbank.payment.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Feign client for banking-service.
 *
 * The payment-service calls banking-service to:
 *   1. Verify the account belongs to the customer and is ACTIVE
 *   2. Check available balance before processing a payment
 *   3. Record the debit transaction after a successful payment
 *
 * Service name 'banking-service' is resolved via Eureka.
 * The X-Customer-Id header is forwarded so banking-service
 * can apply its own BOLA ownership check.
 */
@FeignClient(name = "banking-service", path = "/")
public interface BankingServiceClient {

    @GetMapping("/accounts/{accountId}/balance")
    ApiResponse<AccountCheckDto> getAccountBalance(
        @PathVariable UUID accountId,
        @RequestHeader("X-Customer-Id") Long customerId
    );

    @PostMapping("/accounts/{accountId}/debit")
    void recordDebit(
        @PathVariable UUID accountId,
        @RequestHeader("X-Customer-Id") Long customerId,
        @RequestParam BigDecimal amount,
        @RequestParam String description,
        @RequestParam String referenceNumber,
        @RequestParam String counterpartyAccount,
        @RequestParam String branchCode
    );
}

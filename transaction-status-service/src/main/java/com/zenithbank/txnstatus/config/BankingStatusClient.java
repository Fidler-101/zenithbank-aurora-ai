package com.zenithbank.txnstatus.config;

import com.zenithbank.txnstatus.dto.PaymentDto;
import com.zenithbank.txnstatus.dto.TransactionDto;
import com.zenithbank.txnstatus.dto.FeignWrapper;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * Feign client — banking-service.
 * Used to look up an individual transaction by ID.
 */
@FeignClient(name = "banking-service", contextId = "bankingStatusClient", path = "/")
public interface BankingStatusClient {

    @GetMapping("/transactions/{transactionId}")
    FeignWrapper<TransactionDto> getTransaction(
        @PathVariable UUID transactionId,
        @RequestHeader("X-Customer-Id") Long customerId
    );
}

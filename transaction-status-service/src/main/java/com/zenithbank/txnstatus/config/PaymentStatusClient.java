package com.zenithbank.txnstatus.config;

import com.zenithbank.txnstatus.dto.PaymentDto;
import com.zenithbank.txnstatus.dto.FeignWrapper;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.UUID;

/**
 * Feign client — payment-service.
 * Used to look up a payment record by ID.
 */
@FeignClient(name = "payment-service", contextId = "paymentStatusClient", path = "/")
public interface PaymentStatusClient {

    @GetMapping("/{paymentId}")
    FeignWrapper<PaymentDto> getPayment(
        @PathVariable UUID paymentId,
        @RequestHeader("X-Customer-Id") Long customerId
    );
}

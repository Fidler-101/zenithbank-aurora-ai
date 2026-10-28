package com.zenithbank.txnstatus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * ZenithBank Transaction Status Service — port 8087
 *
 * Lightweight aggregator service.
 * No own database — queries banking-service and payment-service
 * via Feign to resolve the status of any transaction or payment ID.
 *
 * Spec endpoint: GET /api/transactions/{id}/status
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
public class TransactionStatusServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionStatusServiceApplication.class, args);
    }
}

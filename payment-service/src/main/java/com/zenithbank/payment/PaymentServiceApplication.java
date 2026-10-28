package com.zenithbank.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ZenithBank Payment Service — port 8085
 *
 * Handles: payment creation, validation, once-off and recurring
 * payment processing, proof-of-payment PDF generation,
 * and payment cancellation.
 *
 * Calls banking-service via Feign for balance checks
 * and transaction recording.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}

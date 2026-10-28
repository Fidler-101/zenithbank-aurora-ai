package com.zenithbank.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ZenithBank Customer Service — port 8081
 *
 * Unified service covering:
 *  - KYC registration + OTP email verification
 *  - Admin approval workflow
 *  - JWT login / logout
 *  - Customer profile management
 *
 * Registers with Eureka so the API Gateway can route:
 *   /api/customers/** → lb://customer-service
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableAsync
public class CustomerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}

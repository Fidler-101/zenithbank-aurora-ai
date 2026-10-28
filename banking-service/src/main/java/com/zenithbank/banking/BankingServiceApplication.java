package com.zenithbank.banking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ZenithBank Banking Service — port 7777
 *
 * Owns: accounts, transactions, spending analytics, financial health score.
 * Registers with Eureka as 'banking-service'.
 * All data access is ownership-checked against X-Customer-Id from gateway.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableAsync
@EnableScheduling
public class BankingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankingServiceApplication.class, args);
    }
}

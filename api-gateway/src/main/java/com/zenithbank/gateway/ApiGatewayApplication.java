package com.zenithbank.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * ZenithBank API Gateway — port 8080
 *
 * Single entry point for all client traffic.
 * Routes requests to downstream services discovered via Eureka.
 * Validates JWT tokens before forwarding protected routes.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}

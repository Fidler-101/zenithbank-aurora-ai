package com.zenithbank.customer.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TokenResponse {

    private String accessToken;
    private String tokenType    = "Bearer";
    private long   expiresIn;       // seconds
    private String customerName;
    private Long   customerId;
}

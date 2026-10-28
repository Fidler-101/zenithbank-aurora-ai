package com.zenithbank.txnstatus.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Getter @Setter @NoArgsConstructor
public class FeignWrapper<T> {
    private boolean success;
    private String message;
    private T data;
    private String timestamp;
}

package com.zenithbank.banking.dto;

import lombok.*;

import java.util.List;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TransactionPageDto {

    private List<TransactionDto> transactions;
    private int                  page;
    private int                  size;
    private long                 totalElements;
    private int                  totalPages;
    private boolean              last;
}

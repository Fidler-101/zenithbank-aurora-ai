package com.zenithbank.banking.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AsyncJobDto {

    private UUID                 jobId;
    private String               status;
    private String               message;
    private Integer              resultCount;
    private LocalDateTime        createdAt;
    private LocalDateTime        completedAt;
    private List<TransactionDto> transactions; // populated when status = COMPLETED
}

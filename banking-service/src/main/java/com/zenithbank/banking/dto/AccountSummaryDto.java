package com.zenithbank.banking.dto;

import com.zenithbank.banking.entity.Account;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AccountSummaryDto {

    private UUID               id;
    private String             accountNumber;
    private String             accountName;
    private String             accountType;
    private BigDecimal         balance;
    private BigDecimal         availableBalance;
    private String             currency;
    private String             status;
    private BigDecimal         overdraftLimit;
    private List<TransactionDto> recentTransactions;

    public static AccountSummaryDto from(Account a, List<TransactionDto> recent) {
        return AccountSummaryDto.builder()
                .id(a.getId())
                .accountNumber(maskAccountNumber(a.getAccountNumber()))
                .accountName(a.getAccountName())
                .accountType(a.getAccountType().name())
                .balance(a.getBalance())
                .availableBalance(a.getAvailableBalance())
                .currency(a.getCurrency())
                .status(a.getStatus().name())
                .overdraftLimit(a.getOverdraftLimit())
                .recentTransactions(recent)
                .build();
    }

    private static String maskAccountNumber(String number) {
        if (number == null || number.length() < 8) return number;
        return number.substring(0, 4) + "****" + number.substring(number.length() - 4);
    }
}

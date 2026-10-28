package com.zenithbank.payment.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "branches")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_code", nullable = false, unique = true, length = 6)
    private String branchCode;

    @Column(name = "branch_name", nullable = false, length = 100)
    private String branchName;

    @Column(name = "bank_name", nullable = false, length = 100)
    private String bankName;

    @Column(name = "region", nullable = false, length = 50)
    private String region;
}

package com.zenithbank.customer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name",   nullable = false, length = 80)  private String firstName;
    @Column(name = "last_name",    nullable = false, length = 80)  private String lastName;
    @Column(name = "email",        nullable = false, unique = true) private String email;
    @Column(name = "phone_number", nullable = false, unique = true) private String phoneNumber;
    @Column(name = "id_number",    nullable = false, unique = true) private String idNumber;
    @Column(name = "date_of_birth",nullable = false)                private LocalDate dateOfBirth;
    @Column(name = "address_line1",nullable = false, length = 150)  private String addressLine1;
    @Column(name = "address_line2",length = 150)                    private String addressLine2;
    @Column(name = "city",         nullable = false, length = 80)   private String city;
    @Column(name = "province",     nullable = false, length = 80)   private String province;
    @Column(name = "postal_code",  nullable = false, length = 10)   private String postalCode;
    @Column(name = "country",      nullable = false, length = 60)   private String country = "South Africa";
    @Column(name = "password_hash",nullable = false, length = 255)  private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.CUSTOMER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.PENDING;

    @Column(name = "email_verified", nullable = false)
    private Boolean emailVerified = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "approved_at")  private LocalDateTime approvedAt;
    @Column(name = "approved_by")  private String approvedBy;
    @Column(name = "last_login_at") private LocalDateTime lastLoginAt;

    // ---- helpers ----
    public String getFullName()      { return firstName + " " + lastName; }
    public boolean isActive()        { return CustomerStatus.ACTIVE.equals(status); }
    public boolean isOtpVerified()   { return CustomerStatus.OTP_VERIFIED.equals(status); }

    public enum CustomerStatus {
        PENDING, OTP_VERIFIED, ACTIVE, REJECTED, SUSPENDED
    }

    public enum Role {
        CUSTOMER, ADMIN
    }
}

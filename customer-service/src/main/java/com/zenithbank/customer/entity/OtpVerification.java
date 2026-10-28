package com.zenithbank.customer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "otp_verifications")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class OtpVerification {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "otp_code",  nullable = false, length = 6)  private String otpCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "otp_type", nullable = false, length = 30)
    private OtpType otpType = OtpType.EMAIL_VERIFICATION;

    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "used",       nullable = false) private Boolean used = false;
    @Column(name = "used_at")                      private LocalDateTime usedAt;
    @Column(name = "attempts",   nullable = false) private Integer attempts = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isExpired()  { return LocalDateTime.now().isAfter(this.expiresAt); }
    public boolean isValid()    { return !this.used && !isExpired() && this.attempts < 3; }
    public void incrementAttempts() { this.attempts++; }
    public void markUsed() { this.used = true; this.usedAt = LocalDateTime.now(); }

    public enum OtpType { EMAIL_VERIFICATION, PASSWORD_RESET, LOGIN_2FA }
}

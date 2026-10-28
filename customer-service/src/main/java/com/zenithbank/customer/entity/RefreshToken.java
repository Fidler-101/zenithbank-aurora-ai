package com.zenithbank.customer.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class RefreshToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "device_info", length = 255) private String deviceInfo;
    @Column(name = "ip_address",  length = 45)  private String ipAddress;
    @Column(name = "expires_at",  nullable = false) private LocalDateTime expiresAt;
    @Column(name = "revoked",     nullable = false) private Boolean revoked = false;
    @Column(name = "revoked_at")                    private LocalDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isValid() { return !revoked && LocalDateTime.now().isBefore(expiresAt); }
    public void revoke() { this.revoked = true; this.revokedAt = LocalDateTime.now(); }
}

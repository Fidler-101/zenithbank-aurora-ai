package com.zenithbank.customer.service;

import com.zenithbank.customer.entity.Customer;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.SignatureException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * Production-grade JWT Service — RS256 asymmetric signing.
 *
 * Security standards implemented:
 *  - RS256 (RSA + SHA-256) — private key signs, public key verifies
 *  - Full RFC 7519 standard claims: iss, sub, aud, exp, nbf, iat, jti
 *  - JTI denylist in Redis — logout actually invalidates tokens
 *  - 15-min access tokens (FAPI compliant)
 */
@Service
@RequiredArgsConstructor
public class JwtService {
    private static final Logger log = Logger.getLogger(JwtService.class.getName());


    private final RSAPrivateKey rsaPrivateKey;
    private final RSAPublicKey  rsaPublicKey;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.jwt.issuer:https://auth.zenithbank.co.za}")
    private String issuer;

    @Value("${app.jwt.audience:zenithbank-api}")
    private String audience;

    @Value("${app.jwt.access-token-expiry-ms:900000}")
    private long accessTokenExpiryMs;

    private static final String DENYLIST_PREFIX = "jwt:denylist:";

    public String generateAccessToken(Customer customer) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiryMs);
        String jti  = UUID.randomUUID().toString();

        return Jwts.builder()
                .id(jti)
                .issuer(issuer)
                .subject(customer.getId().toString())
                .audience().add(audience).and()
                .issuedAt(now)
                .notBefore(now)
                .expiration(expiry)
                .claim("email",  customer.getEmail())
                .claim("role",   customer.getRole().name())
                .claim("name",   customer.getFullName())
                .claim("status", customer.getStatus().name())
                .signWith(rsaPrivateKey, Jwts.SIG.RS256)
                .compact();
    }

    public Claims validateAndParseClaims(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(rsaPublicKey)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new JwtException("Token has expired. Please log in again.");
        } catch (SignatureException e) {
            throw new JwtException("Invalid token signature.");
        } catch (MalformedJwtException e) {
            throw new JwtException("Malformed token.");
        } catch (Exception e) {
            throw new JwtException("Token validation failed: " + e.getMessage());
        }

        if (isTokenRevoked(claims.getId())) {
            throw new JwtException("Token has been revoked. Please log in again.");
        }
        return claims;
    }

    public void revokeToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(rsaPublicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remainingMs > 0) {
                redisTemplate.opsForValue().set(
                    DENYLIST_PREFIX + claims.getId(), "revoked",
                    Duration.ofMillis(remainingMs)
                );
                log.info("Token JTI " + claims.getId() + " revoked (TTL: " + remainingMs + "ms)");
            }
        } catch (Exception e) {
            log.warning("Could not revoke token: " + e.getMessage());
        }
    }

    public boolean isTokenRevoked(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(DENYLIST_PREFIX + jti));
    }

    public long getAccessTokenExpiryMs() { return accessTokenExpiryMs; }

    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(rsaPublicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

}
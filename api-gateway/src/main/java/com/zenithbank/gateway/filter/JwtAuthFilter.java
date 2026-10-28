package com.zenithbank.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.logging.Logger;

@Component
public class JwtAuthFilter extends AbstractGatewayFilterFactory<JwtAuthFilter.Config> {

    private static final Logger log = Logger.getLogger(JwtAuthFilter.class.getName());

    @Value("${app.jwt.public-key-path:./keys/public_key.pem}")
    private String publicKeyPath;

    @Value("${app.jwt.issuer:https://auth.zenithbank.co.za}")
    private String expectedIssuer;

    @Value("${app.jwt.audience:zenithbank-api}")
    private String expectedAudience;

    public JwtAuthFilter() { super(Config.class); }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warning("Missing Authorization header: " + exchange.getRequest().getPath().toString());
                return unauthorised(exchange, "Missing or invalid Authorization header.");
            }
            String token = authHeader.substring(7);
            try {
                RSAPublicKey publicKey = loadPublicKey();
                Claims claims = Jwts.parser()
                        .verifyWith(publicKey)
                        .requireIssuer(expectedIssuer)
                        .requireAudience(expectedAudience)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                String customerId = claims.getSubject();
                String email  = claims.get("email",  String.class);
                String role   = claims.get("role",   String.class);
                String status = claims.get("status", String.class);
                if ("SUSPENDED".equals(status)) {
                    log.warning("Suspended customer blocked: " + customerId);
                    return unauthorised(exchange, "Account is suspended.");
                }
                log.fine("JWT validated for customer: " + customerId);
                final String finalEmail = email != null ? email : "";
                final String finalRole = role != null ? role : "CUSTOMER";
                org.springframework.http.server.reactive.ServerHttpRequestDecorator decoratedRequest =
                        new org.springframework.http.server.reactive.ServerHttpRequestDecorator(exchange.getRequest()) {
                    @Override
                    public HttpHeaders getHeaders() {
                        HttpHeaders headers = new HttpHeaders();
                        headers.putAll(super.getHeaders());
                        headers.set("X-Customer-Id", customerId);
                        headers.set("X-Customer-Email", finalEmail);
                        headers.set("X-Customer-Role", finalRole);
                        return headers;
                    }
                };
                return chain.filter(exchange.mutate().request(decoratedRequest).build());
            } catch (JwtException e) {
                log.warning("JWT invalid: " + e.getMessage());
                return unauthorised(exchange, "Invalid or expired token.");
            } catch (Exception e) {
                log.severe("Auth error [" + e.getClass().getName() + "]: " + e.getMessage());
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                log.severe(sw.toString());
                return unauthorised(exchange, "Authentication error.");
            }
        };
    }

    private Mono<Void> unauthorised(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("X-Auth-Error", reason);
        return exchange.getResponse().setComplete();
    }

    private RSAPublicKey loadPublicKey() throws Exception {
        String pem = new String(Files.readAllBytes(new File(publicKeyPath).toPath()))
                .replaceAll("-----.*-----", "").replaceAll("\\s+", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }

    public static class Config {}
}

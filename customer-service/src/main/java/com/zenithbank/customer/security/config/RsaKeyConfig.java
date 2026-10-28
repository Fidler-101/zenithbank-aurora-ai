package com.zenithbank.customer.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.logging.Logger;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA Key Configuration — RS256 for JWT signing.
 *
 * Banking-grade standard: asymmetric signing means the private key
 * only lives in the customer-service, while all other services
 * only need the public key to verify tokens. A compromised
 * downstream service cannot forge tokens.
 *
 * Keys are generated on first boot and persisted to disk.
 * In production, replace with keys loaded from a secrets manager
 * (AWS Secrets Manager, HashiCorp Vault, Azure Key Vault).
 */
@Configuration
public class RsaKeyConfig {
    private static final Logger log = Logger.getLogger(RsaKeyConfig.class.getName());


    @Value("${app.jwt.private-key-path:./keys/private_key.pem}")
    private String privateKeyPath;

    @Value("${app.jwt.public-key-path:./keys/public_key.pem}")
    private String publicKeyPath;

    @Bean
    public RSAPrivateKey rsaPrivateKey() throws Exception {
        File privateFile = new File(privateKeyPath);
        if (!privateFile.exists()) {
            generateAndPersistKeyPair();
        }
        return loadPrivateKey(privateFile);
    }

    @Bean
    public RSAPublicKey rsaPublicKey() throws Exception {
        File publicFile = new File(publicKeyPath);
        if (!publicFile.exists()) {
            generateAndPersistKeyPair();
        }
        return loadPublicKey(publicFile);
    }

    // ---- Key generation ----

    private void generateAndPersistKeyPair() throws Exception {
        log.info("Generating RSA-2048 key pair for JWT signing...");
        new File("./keys").mkdirs();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        writeKey(privateKeyPath,
                "PRIVATE KEY",
                keyPair.getPrivate().getEncoded());

        writeKey(publicKeyPath,
                "PUBLIC KEY",
                keyPair.getPublic().getEncoded());

        log.info("RSA key pair generated and saved to ./keys/");
    }

    private void writeKey(String path, String type, byte[] encoded) throws Exception {
        String pem = "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END " + type + "-----\n";
        try (FileWriter fw = new FileWriter(path)) { fw.write(pem); }
    }

    // ---- Key loading ----

    private RSAPrivateKey loadPrivateKey(File file) throws Exception {
        String pem = new String(Files.readAllBytes(file.toPath()))
                .replaceAll("-----.*-----", "").replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
    }

    private RSAPublicKey loadPublicKey(File file) throws Exception {
        String pem = new String(Files.readAllBytes(file.toPath()))
                .replaceAll("-----.*-----", "").replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(pem);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(decoded));
    }
}

package com.gitutility.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * Enterprise-grade AES-256-GCM symmetric encryption component.
 * Provides authenticated encryption for all SCM tokens, private keys, and webhook secrets.
 */
@Component
@Slf4j
public class CryptoService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final int SALT_LENGTH_BYTE = 16;
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH_BIT = 256;
    private static final int MIN_SECRET_LENGTH = 32;
    private static final String FORBIDDEN_DEFAULT_KEY = "git-utility-default-secure-master-key-32b";

    private static CryptoService instance;

    @Value("${git-utility.security.encryption-key:}")
    private String masterSecret;

    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    public void init() {
        if (masterSecret == null || masterSecret.isBlank() || FORBIDDEN_DEFAULT_KEY.equals(masterSecret)) {
            throw new IllegalStateException(
                    "GIT_UTILITY_ENCRYPTION_KEY must be set to a unique secret of at least 32 characters. "
                            + "Generate one with: openssl rand -hex 32");
        }
        if (masterSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("GIT_UTILITY_ENCRYPTION_KEY must be at least 32 characters.");
        }
        instance = this;
        log.info("CryptoService initialized with AES-256-GCM envelope encryption.");
    }

    public static CryptoService getInstance() {
        return instance;
    }

    /**
     * Encrypts plaintext string using AES-256-GCM with a fresh random IV and salt.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }

        try {
            byte[] salt = new byte[SALT_LENGTH_BYTE];
            secureRandom.nextBytes(salt);

            byte[] iv = new byte[IV_LENGTH_BYTE];
            secureRandom.nextBytes(iv);

            SecretKey secretKey = deriveKey(masterSecret, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prefix: salt (16 bytes) + iv (12 bytes) + ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(salt.length + iv.length + ciphertext.length);
            byteBuffer.put(salt);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return "enc:v1:" + Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Failed to encrypt secret payload: {}", e.getMessage());
            throw new RuntimeException("Encryption failure", e);
        }
    }

    /**
     * Decrypts AES-256-GCM encrypted payload.
     */
    public String decrypt(String encryptedPayload) {
        if (encryptedPayload == null || encryptedPayload.isBlank()) {
            return null;
        }

        // If not matching encrypted prefix, return as-is for backward compatibility
        if (!encryptedPayload.startsWith("enc:v1:")) {
            return encryptedPayload;
        }

        try {
            String base64Payload = encryptedPayload.substring("enc:v1:".length());
            byte[] decoded = Base64.getDecoder().decode(base64Payload);

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);

            byte[] salt = new byte[SALT_LENGTH_BYTE];
            byteBuffer.get(salt);

            byte[] iv = new byte[IV_LENGTH_BYTE];
            byteBuffer.get(iv);

            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            SecretKey secretKey = deriveKey(masterSecret, salt);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt secret payload: {}", e.getMessage());
            throw new RuntimeException("Decryption failure", e);
        }
    }

    /**
     * Helper to mask secrets for API responses (e.g., ghp_••••••••••••34a).
     */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        String clean = secret.trim();
        if (clean.length() <= 8) {
            return "••••••••";
        }
        String prefix = clean.substring(0, Math.min(4, clean.length() / 4));
        String suffix = clean.substring(clean.length() - Math.min(4, clean.length() / 4));
        return prefix + "••••••••••••" + suffix;
    }

    private SecretKey deriveKey(String password, byte[] salt) throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BIT);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }
}

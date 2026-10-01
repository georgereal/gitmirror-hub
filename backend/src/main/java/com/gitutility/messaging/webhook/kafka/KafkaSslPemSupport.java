package com.gitutility.messaging.webhook.kafka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads PEM files for Kafka and Schema Registry mutual TLS.
 * Kafka's Java client expects certificate material in {@code ssl.*.certificates} /
 * {@code ssl.keystore.certificate.chain} / {@code ssl.keystore.key}, not a separate key-location property.
 * A keystore PEM may be cert-only, or a combined file with certificate(s) plus an (encrypted) private key.
 */
public final class KafkaSslPemSupport {

    private static final Pattern CERTIFICATE = Pattern.compile(
            "-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----",
            Pattern.DOTALL);
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:ENCRYPTED )?PRIVATE KEY-----.*?-----END (?:ENCRYPTED )?PRIVATE KEY-----"
                    + "|-----BEGIN RSA PRIVATE KEY-----.*?-----END RSA PRIVATE KEY-----",
            Pattern.DOTALL);

    private KafkaSslPemSupport() {
    }

    public record PemMaterial(String trustCertificates, String clientCertificateChain, String clientKey) {
    }

    /**
     * @param keyPath optional when {@code keystorePath} already contains a private key block
     */
    public static PemMaterial load(String trustPath, String keystorePath, String keyPath) {
        requireReadable("GIT_WEBHOOK_KAFKA_SSL_TRUSTSTORE_LOCATION", trustPath);
        requireReadable("GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION", keystorePath);
        String trust = read(trustPath);
        String keystorePem = read(keystorePath);
        String chain = joinBlocks(CERTIFICATE, keystorePem);
        String key;
        if (keyPath == null || keyPath.isBlank()) {
            key = firstBlock(PRIVATE_KEY, keystorePem);
            if (chain.isBlank()) {
                throw new IllegalStateException(
                        "GIT_WEBHOOK_KAFKA_SSL_KEYSTORE_LOCATION has no CERTIFICATE block: " + keystorePath);
            }
            if (key.isBlank()) {
                throw new IllegalStateException(
                        "GIT_WEBHOOK_KAFKA_SSL_KEY_LOCATION is required when the keystore PEM has no private key");
            }
        } else {
            requireReadable("GIT_WEBHOOK_KAFKA_SSL_KEY_LOCATION", keyPath);
            String keyPem = read(keyPath);
            key = firstBlock(PRIVATE_KEY, keyPem);
            if (key.isBlank()) {
                key = keyPem.trim();
            }
            if (chain.isBlank()) {
                chain = keystorePem.trim();
            }
        }
        return new PemMaterial(trust, chain, key);
    }

    public static void requireKeyPasswordIfEncrypted(String clientKey, String keyPassword) {
        if (clientKey != null
                && clientKey.contains("BEGIN ENCRYPTED PRIVATE KEY")
                && (keyPassword == null || keyPassword.isBlank())) {
            throw new IllegalStateException(
                    "Encrypted PEM private key requires GIT_WEBHOOK_KAFKA_SSL_KEY_PASSWORD");
        }
    }

    public static void putKafkaSsl(Map<String, Object> overrides, PemMaterial pem, String keyPassword) {
        overrides.put("spring.kafka.properties.ssl.truststore.type", "PEM");
        overrides.put("spring.kafka.properties.ssl.truststore.certificates", pem.trustCertificates());
        overrides.put("spring.kafka.properties.ssl.keystore.type", "PEM");
        overrides.put("spring.kafka.properties.ssl.keystore.certificate.chain", pem.clientCertificateChain());
        overrides.put("spring.kafka.properties.ssl.keystore.key", pem.clientKey());
        if (keyPassword != null && !keyPassword.isBlank()) {
            overrides.put("spring.kafka.properties.ssl.key.password", keyPassword);
        }
    }

    public static void putSchemaRegistrySsl(Map<String, Object> overrides, PemMaterial pem, String keyPassword) {
        overrides.put("spring.kafka.properties.schema.registry.ssl.truststore.type", "PEM");
        overrides.put("spring.kafka.properties.schema.registry.ssl.truststore.certificates",
                pem.trustCertificates());
        overrides.put("spring.kafka.properties.schema.registry.ssl.keystore.type", "PEM");
        overrides.put("spring.kafka.properties.schema.registry.ssl.keystore.certificate.chain",
                pem.clientCertificateChain());
        overrides.put("spring.kafka.properties.schema.registry.ssl.keystore.key", pem.clientKey());
        if (keyPassword != null && !keyPassword.isBlank()) {
            overrides.put("spring.kafka.properties.schema.registry.ssl.key.password", keyPassword);
        }
    }

    private static void requireReadable(String envName, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException(
                    "GIT_WEBHOOK_KAFKA_SECURITY_PROTOCOL=SSL requires " + envName);
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new IllegalStateException(envName + " is not a readable file: " + path);
        }
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read PEM file " + path + ": " + e.getMessage(), e);
        }
    }

    private static String joinBlocks(Pattern pattern, String pem) {
        if (pem == null || pem.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Matcher matcher = pattern.matcher(pem);
        while (matcher.find()) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(matcher.group().trim());
        }
        return out.toString();
    }

    private static String firstBlock(Pattern pattern, String pem) {
        if (pem == null || pem.isBlank()) {
            return "";
        }
        Matcher matcher = pattern.matcher(pem);
        return matcher.find() ? matcher.group().trim() : "";
    }
}

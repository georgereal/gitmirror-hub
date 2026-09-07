package com.gitutility.security;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/** RS256 JWT for authenticating as a GitHub / GHES App. */
public final class GitHubAppJwt {

    private GitHubAppJwt() {
    }

    public static String generate(String appId, String privateKeyPem) throws Exception {
        long now = Instant.now().getEpochSecond();
        long exp = now + (9 * 60);
        long iat = now - 60;

        String headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payloadJson = String.format("{\"iat\":%d,\"exp\":%d,\"iss\":\"%s\"}", iat, exp, appId.trim());

        String headerEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String unsignedToken = headerEncoded + "." + payloadEncoded;

        PrivateKey privateKey = parsePrivateKey(privateKeyPem);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(unsignedToken.getBytes(StandardCharsets.UTF_8));
        byte[] sigBytes = signature.sign();
        String sigEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
        return unsignedToken + "." + sigEncoded;
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair keyPair) {
                return converter.getPrivateKey(keyPair.getPrivateKeyInfo());
            } else if (obj instanceof PrivateKeyInfo keyInfo) {
                return converter.getPrivateKey(keyInfo);
            }
            throw new IllegalArgumentException("Unsupported RSA private key format");
        }
    }
}

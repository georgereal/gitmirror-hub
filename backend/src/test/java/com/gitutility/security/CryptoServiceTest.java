package com.gitutility.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class CryptoServiceTest {

    private CryptoService cryptoService;

    @BeforeEach
    void setUp() {
        cryptoService = new CryptoService();
        ReflectionTestUtils.setField(cryptoService, "masterSecret", "my-super-secret-master-key-12345");
        cryptoService.init();
    }

    @Test
    void testEncryptAndDecrypt() {
        String secretToken = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";

        String encrypted = cryptoService.encrypt(secretToken);
        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertNotEquals(secretToken, encrypted);

        String decrypted = cryptoService.decrypt(encrypted);
        assertEquals(secretToken, decrypted);
    }

    @Test
    void testMasking() {
        String token = "ghp_1234567890abcdef";
        String masked = CryptoService.mask(token);

        assertTrue(masked.contains("••••"));
        assertFalse(masked.contains("1234567890"));
        assertTrue(masked.startsWith("ghp_"));
    }

    @Test
    void testNullAndEmptyHandling() {
        assertNull(cryptoService.encrypt(null));
        assertNull(cryptoService.decrypt(null));
        assertEquals("", CryptoService.mask(""));
    }

    @Test
    void initRejectsMissingOrDefaultMasterKey() {
        CryptoService uninitialized = new CryptoService();
        ReflectionTestUtils.setField(uninitialized, "masterSecret", "");
        IllegalStateException blank = assertThrows(IllegalStateException.class, uninitialized::init);
        assertTrue(blank.getMessage().contains("GIT_UTILITY_ENCRYPTION_KEY"));

        ReflectionTestUtils.setField(uninitialized, "masterSecret", "git-utility-default-secure-master-key-32b");
        assertThrows(IllegalStateException.class, uninitialized::init);

        ReflectionTestUtils.setField(uninitialized, "masterSecret", "too-short");
        assertThrows(IllegalStateException.class, uninitialized::init);
    }
}

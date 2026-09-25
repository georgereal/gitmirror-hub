package com.gitutility.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a string attribute as encrypted at rest. JPA applies this via
 * {@link EncryptedStringConverter}; the MongoDB store applies it via the Mongo
 * lifecycle listener (same {@link CryptoService}, same {@code enc:v1:} ciphertext
 * format — data written by either store decrypts in both).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Encrypted {
}

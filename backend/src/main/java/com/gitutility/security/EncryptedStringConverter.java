package com.gitutility.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter that automatically encrypts sensitive string attributes
 * before database writes and decrypts them upon database reads.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        CryptoService cryptoService = CryptoService.getInstance();
        if (cryptoService != null) {
            return cryptoService.encrypt(attribute);
        }
        return attribute;
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        CryptoService cryptoService = CryptoService.getInstance();
        if (cryptoService != null) {
            return cryptoService.decrypt(dbData);
        }
        return dbData;
    }
}

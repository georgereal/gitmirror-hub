package com.gitutility.persistence.store;

import com.gitutility.security.CryptoService;
import com.gitutility.security.Encrypted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterConvertEvent;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * MongoDB lifecycle listener giving the Mongo store the same write preparation and
 * at-rest encryption the JPA store gets from {@code @PrePersist} and
 * {@link com.gitutility.security.EncryptedStringConverter}: entities implementing
 * {@link WritePreparer} are prepared before conversion, and {@link Encrypted @Encrypted}
 * string fields are encrypted on write / decrypted on read using the same
 * {@link CryptoService} AES-256-GCM {@code enc:v1:} ciphertext format.
 */
@Component
@Slf4j
public class MongoEntityLifecycleListener extends AbstractMongoEventListener<Object> {

    private static final Map<Class<?>, List<Field>> ENCRYPTED_FIELDS = new ConcurrentHashMap<>();

    @Override
    public void onBeforeConvert(BeforeConvertEvent<Object> event) {
        Object source = event.getSource();
        if (source instanceof WritePreparer preparer) {
            preparer.prepareForWrite();
        }
        CryptoService crypto = CryptoService.getInstance();
        if (crypto != null) {
            transformEncrypted(source, crypto::encrypt);
        }
    }

    @Override
    public void onAfterConvert(AfterConvertEvent<Object> event) {
        CryptoService crypto = CryptoService.getInstance();
        if (crypto != null) {
            transformEncrypted(event.getSource(), crypto::decrypt);
        }
    }

    private void transformEncrypted(Object source, UnaryOperator<String> op) {
        if (source == null) {
            return;
        }
        try {
            for (Field field : encryptedFieldsOf(source.getClass())) {
                Object value = field.get(source);
                if (value instanceof String s && !s.isBlank()) {
                    field.set(source, op.apply(s));
                }
            }
        } catch (Exception e) {
            log.error("Mongo encrypted-field transformation failed for {}: {}",
                    source.getClass().getSimpleName(), e.getMessage());
            throw new IllegalStateException("Mongo encrypted-field transformation failure", e);
        }
    }

    private static List<Field> encryptedFieldsOf(Class<?> type) {
        return ENCRYPTED_FIELDS.computeIfAbsent(type, t -> Arrays.stream(t.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Encrypted.class))
                .peek(f -> f.setAccessible(true))
                .toList());
    }
}

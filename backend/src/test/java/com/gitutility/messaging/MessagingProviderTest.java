package com.gitutility.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagingProviderTest {

    @Test
    void parsesCanonicalAndAliases() {
        assertEquals(MessagingProvider.RABBITMQ, MessagingProvider.from("rabbitmq"));
        assertEquals(MessagingProvider.RABBITMQ, MessagingProvider.from("rabbitMq"));
        assertEquals(MessagingProvider.RABBITMQ, MessagingProvider.from("rabbit"));
        assertEquals(MessagingProvider.KAFKA, MessagingProvider.from("kafka"));
        assertEquals(MessagingProvider.NONE, MessagingProvider.from("none"));
        assertEquals(MessagingProvider.NONE, MessagingProvider.from("inline"));
        assertEquals(MessagingProvider.RABBITMQ, MessagingProvider.from(null));
        assertEquals(MessagingProvider.RABBITMQ, MessagingProvider.from(""));
    }

    @Test
    void wireIdsAreStable() {
        assertEquals("rabbitmq", MessagingProvider.RABBITMQ.wireId());
        assertEquals("kafka", MessagingProvider.KAFKA.wireId());
        assertEquals("none", MessagingProvider.NONE.wireId());
    }

    @Test
    void rejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> MessagingProvider.from("sqs"));
    }
}

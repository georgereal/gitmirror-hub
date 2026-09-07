package com.gitutility.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueueControllerTest {

    @Test
    void maskBrokerAddressHidesPassword() {
        assertEquals(
                "amqps://user:••••@host.example/vhost",
                QueueController.maskBrokerAddress("amqps://user:s3cret@host.example/vhost")
        );
        assertEquals("amqp://localhost:5672", QueueController.maskBrokerAddress("amqp://localhost:5672"));
        assertNull(QueueController.maskBrokerAddress(null));
    }
}

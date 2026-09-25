package com.gitutility.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobCancellationServiceTest {

    private JobCancellationService service;

    @BeforeEach
    void setUp() {
        service = new JobCancellationService();
    }

    @Test
    void requestCancelIsVisibleToWorkers() {
        assertFalse(service.isCancelRequested("42"));
        service.requestCancel("42");
        assertTrue(service.isCancelRequested("42"));
        assertFalse(service.isCancelRequested("99"));
    }

    @Test
    void requestPauseIsVisibleToWorkers() {
        assertFalse(service.isPauseRequested("42"));
        service.requestPause("42");
        assertTrue(service.isPauseRequested("42"));
        assertFalse(service.isCancelRequested("42"));
    }

    @Test
    void requestCancelClearsPauseFlag() {
        service.requestPause("7");
        service.requestCancel("7");
        assertFalse(service.isPauseRequested("7"));
        assertTrue(service.isCancelRequested("7"));
    }

    @Test
    void unregisterClearsCancelFlag() {
        service.requestCancel("7");
        service.unregisterRunning("7");
        assertFalse(service.isCancelRequested("7"));
    }

    @Test
    void requestCancelDoesNotInterruptListenerThread() throws Exception {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        service.registerRunning("1", worker);
        service.requestCancel("1");
        Thread.sleep(50);
        assertTrue(worker.isAlive(), "AMQP listener thread must stay alive so cancelled messages can drain");
        assertTrue(service.isCancelRequested("1"));
        worker.join(1000);
    }
}

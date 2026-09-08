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
        assertFalse(service.isCancelRequested(42L));
        service.requestCancel(42L);
        assertTrue(service.isCancelRequested(42L));
        assertFalse(service.isCancelRequested(99L));
    }

    @Test
    void requestPauseIsVisibleToWorkers() {
        assertFalse(service.isPauseRequested(42L));
        service.requestPause(42L);
        assertTrue(service.isPauseRequested(42L));
        assertFalse(service.isCancelRequested(42L));
    }

    @Test
    void requestCancelClearsPauseFlag() {
        service.requestPause(7L);
        service.requestCancel(7L);
        assertFalse(service.isPauseRequested(7L));
        assertTrue(service.isCancelRequested(7L));
    }

    @Test
    void unregisterClearsCancelFlag() {
        service.requestCancel(7L);
        service.unregisterRunning(7L);
        assertFalse(service.isCancelRequested(7L));
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
        service.registerRunning(1L, worker);
        service.requestCancel(1L);
        Thread.sleep(50);
        assertTrue(worker.isAlive(), "AMQP listener thread must stay alive so cancelled messages can drain");
        assertTrue(service.isCancelRequested(1L));
        worker.join(1000);
    }
}

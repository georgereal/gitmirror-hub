package com.gitutility.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    @Test
    void blockingQueueHandlerWaitsForASlotInsteadOfRejecting() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                new ThreadPoolExecutor.AbortPolicy());
        pool.setRejectedExecutionHandler(AsyncConfig.blockingQueueHandler());

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pool.execute(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        pool.execute(() -> { });

        AtomicBoolean submitted = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            pool.execute(() -> { });
            submitted.set(true);
        }, "lfs-block-test");
        waiter.start();
        Thread.sleep(80);
        assertTrue(waiter.isAlive(), "third submit should block until a queue slot frees");
        assertFalse(submitted.get());

        release.countDown();
        waiter.join(2000);
        assertFalse(waiter.isAlive());
        assertTrue(submitted.get());
        pool.shutdownNow();
    }
}

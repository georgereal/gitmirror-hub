package com.gitutility.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Semaphore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushBatchConcurrencyServiceTest {

    private PushBatchConcurrencyService service(int perJob, int pool, int perHost, long cooldownSeconds) {
        PushBatchConcurrencyService service = new PushBatchConcurrencyService("/tmp/git-utility-mirrors");
        ReflectionTestUtils.setField(service, "maxPerJobConcurrency", perJob);
        ReflectionTestUtils.setField(service, "poolMaxThreads", pool);
        ReflectionTestUtils.setField(service, "perHostMax", perHost);
        ReflectionTestUtils.setField(service, "throttleCooldownSeconds", cooldownSeconds);
        ReflectionTestUtils.setField(service, "cpuHighWatermark", 0.90);
        ReflectionTestUtils.setField(service, "heapHighWatermarkPercent", 85.0);
        ReflectionTestUtils.setField(service, "diskLowWatermarkPercent", 10.0);
        return service;
    }

    /** Wave-size tests need deterministic resource headroom (the live probe depends on the test machine). */
    private PushBatchConcurrencyService serviceWithHeadroom(int perJob, int pool, int perHost, long cooldownSeconds) {
        PushBatchConcurrencyService spy = Mockito.spy(service(perJob, pool, perHost, cooldownSeconds));
        Mockito.doReturn(true).when(spy).hasResourceHeadroom();
        return spy;
    }

    @Test
    void configuredPerJobMaxCapsWave() {
        PushBatchConcurrencyService service = serviceWithHeadroom(2, 8, 4, 60);
        service.jobBegin(1L);
        assertEquals(2, service.waveSize(1L));
    }

    @Test
    void fairShareSplitsPoolAcrossActiveJobs() {
        PushBatchConcurrencyService service = serviceWithHeadroom(4, 8, 4, 60);
        service.jobBegin(1L);
        assertEquals(4, service.waveSize(1L));
        service.jobBegin(2L);
        service.jobBegin(3L);
        service.jobBegin(4L);
        assertEquals(2, service.waveSize(1L));
        service.jobEnd(2L);
        service.jobEnd(3L);
        service.jobEnd(4L);
        assertEquals(4, service.waveSize(1L));
    }

    @Test
    void waveNeverExceedsPoolSize() {
        PushBatchConcurrencyService service = serviceWithHeadroom(8, 4, 4, 60);
        service.jobBegin(1L);
        assertEquals(4, service.waveSize(1L));
    }

    @Test
    void throttleCooldownCollapsesWaveToOneUntilExpired() {
        PushBatchConcurrencyService service = serviceWithHeadroom(4, 8, 4, 60);
        service.jobBegin(1L);

        service.recordThrottle("test throttle");

        assertTrue(service.isThrottleCooldownActive());
        assertEquals(1, service.waveSize(1L));
        assertTrue(service.throttleCooldownRemainingMs() > 0);

        // Expire the cooldown.
        ReflectionTestUtils.setField(service, "throttleCooldownUntilMs", System.currentTimeMillis() - 1);
        assertFalse(service.isThrottleCooldownActive());
        assertEquals(4, service.waveSize(1L));
    }

    @Test
    void resourcePressureCollapsesWaveToOne() {
        PushBatchConcurrencyService service = service(4, 8, 4, 0);
        PushBatchConcurrencyService spy = Mockito.spy(service);
        service.jobBegin(1L);

        Mockito.doReturn(false).when(spy).hasResourceHeadroom();
        assertEquals(1, spy.waveSize(1L));

        Mockito.doReturn(true).when(spy).hasResourceHeadroom();
        assertEquals(4, spy.waveSize(1L));
    }

    @Test
    void hostSemaphoreCapsSimultaneousConnectionsPerHost() {
        PushBatchConcurrencyService service = service(2, 8, 2, 60);

        Semaphore github = service.hostSemaphore("https://github.com/owner/repo.git");
        Semaphore githubAlias = service.hostSemaphore("https://github.com/other/repo.git");
        Semaphore gitlab = service.hostSemaphore("https://gitlab.com/owner/repo.git");

        assertTrue(github.tryAcquire());
        assertTrue(githubAlias.tryAcquire());          // same host key → same semaphore instance
        assertFalse(github.tryAcquire());              // per-host cap of 2 reached
        assertTrue(gitlab.tryAcquire());               // different host unaffected
        github.release();
        githubAlias.release();
        gitlab.release();
    }

    @Test
    void hostKeyIsLowercasedHostOrRawFallback() {
        PushBatchConcurrencyService service = service(2, 8, 4, 60);
        assertEquals("github.com", service.hostKey("https://GitHub.com/owner/repo.git"));
        assertEquals("gitlab.com", service.hostKey("https://user:token@gitlab.com/owner/repo.git"));
        assertEquals("/some/local/path", service.hostKey("/some/local/path"));
        assertEquals("unknown", service.hostKey(null));
    }

    @Test
    void inFlightBatchCounterTracksBeginEnd() {
        PushBatchConcurrencyService service = service(2, 8, 4, 60);
        assertEquals(0, service.inFlightBatchCount());
        service.beginBatch();
        service.beginBatch();
        assertEquals(2, service.inFlightBatchCount());
        service.endBatch();
        assertEquals(1, service.inFlightBatchCount());
        service.endBatch();
        assertEquals(0, service.inFlightBatchCount());
    }
}
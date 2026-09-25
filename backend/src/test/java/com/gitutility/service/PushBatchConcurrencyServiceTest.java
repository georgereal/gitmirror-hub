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
        service.jobBegin("1");
        assertEquals(2, service.waveSize("1"));
    }

    @Test
    void fairShareSplitsPoolAcrossActiveJobs() {
        PushBatchConcurrencyService service = serviceWithHeadroom(4, 8, 4, 60);
        service.jobBegin("1");
        assertEquals(4, service.waveSize("1"));
        service.jobBegin("2");
        service.jobBegin("3");
        service.jobBegin("4");
        assertEquals(2, service.waveSize("1"));
        service.jobEnd("2");
        service.jobEnd("3");
        service.jobEnd("4");
        assertEquals(4, service.waveSize("1"));
    }

    @Test
    void waveNeverExceedsPoolSize() {
        PushBatchConcurrencyService service = serviceWithHeadroom(8, 4, 4, 60);
        service.jobBegin("1");
        assertEquals(4, service.waveSize("1"));
    }

    @Test
    void throttleCooldownCollapsesWaveToOneUntilExpired() {
        PushBatchConcurrencyService service = serviceWithHeadroom(4, 8, 4, 60);
        service.jobBegin("1");

        service.recordThrottle("test throttle");

        assertTrue(service.isThrottleCooldownActive());
        assertEquals(1, service.waveSize("1"));
        assertTrue(service.throttleCooldownRemainingMs() > 0);

        // Expire the cooldown.
        ReflectionTestUtils.setField(service, "throttleCooldownUntilMs", System.currentTimeMillis() - 1);
        assertFalse(service.isThrottleCooldownActive());
        assertEquals(4, service.waveSize("1"));
    }

    @Test
    void resourcePressureCollapsesWaveToOne() {
        PushBatchConcurrencyService service = service(4, 8, 4, 0);
        PushBatchConcurrencyService spy = Mockito.spy(service);
        service.jobBegin("1");

        Mockito.doReturn(false).when(spy).hasResourceHeadroom();
        assertEquals(1, spy.waveSize("1"));

        Mockito.doReturn(true).when(spy).hasResourceHeadroom();
        assertEquals(4, spy.waveSize("1"));
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
    void firstThrottleScalesWithLivePodsAndLocalPairs() {
        PushBatchConcurrencyService service = Mockito.spy(service(4, 8, 4, 60));
        Mockito.doReturn(0L).when(service).nextJitterMs(Mockito.anyLong());
        service.pairBegin("a");
        service.pairBegin("b");

        service.recordThrottle("HTTP 429");

        long remain = service.throttleCooldownRemainingMs();
        assertTrue(remain >= 119_000L && remain <= 121_000L, "expected about 120s, was " + remain);
    }

    @Test
    void laterThrottleAddsJitterWithoutRestartingTheBase() {
        PushBatchConcurrencyService service = Mockito.spy(service(4, 8, 4, 60));
        Mockito.doReturn(0L).when(service).nextJitterMs(Mockito.anyLong());
        service.recordThrottle("HTTP 429");
        long first = service.throttleCooldownRemainingMs();

        Mockito.doReturn(5_000L).when(service).nextJitterMs(Mockito.anyLong());
        service.recordThrottle("HTTP 429 again");
        long second = service.throttleCooldownRemainingMs();

        assertTrue(second > first);
        assertTrue(second - first < 20_000L, "later 429 should add jitter, not another full base");
    }

    @Test
    void retryAfterIsAFloorOnTheFirstThrottle() {
        PushBatchConcurrencyService service = Mockito.spy(service(2, 8, 4, 60));
        Mockito.doReturn(0L).when(service).nextJitterMs(Mockito.anyLong());

        service.recordThrottle("429 Retry-After: 120");

        long remain = service.throttleCooldownRemainingMs();
        assertTrue(remain >= 119_000L && remain <= 121_000L, "expected Retry-After 120s, was " + remain);
    }

    @Test
    void cooldownCapStopsTheDeadlineGrowing() {
        PushBatchConcurrencyService service = Mockito.spy(service(4, 8, 4, 60));
        ReflectionTestUtils.setField(service, "throttleCooldownMaxSeconds", 10L);
        Mockito.doReturn(0L).when(service).nextJitterMs(Mockito.anyLong());
        service.pairBegin("a");
        service.pairBegin("b");
        service.pairBegin("c");

        service.recordThrottle("HTTP 429");

        long remain = service.throttleCooldownRemainingMs();
        assertTrue(remain <= 11_000L, "cap should hold the wait near 10s, was " + remain);
        assertTrue(remain > 0);
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
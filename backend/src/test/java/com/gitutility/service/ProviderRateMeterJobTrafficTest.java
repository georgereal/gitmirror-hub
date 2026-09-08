package com.gitutility.service;

import com.gitutility.model.entity.SyncJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderRateMeterJobTrafficTest {

    private final ProviderRateMeter meter = new ProviderRateMeter(null, null, null, null, null, null);

    @AfterEach
    void unbind() {
        meter.unbindJob();
    }

    @Test
    void copyToPersistsRunAveragesAndCallVolumeSeriesWithoutQuotaRemaining() throws Exception {
        meter.bindJob(42L);
        intercept("https://api.github.com/repos/acme/app/contents");
        intercept("https://api.github.com/graphql");
        meter.recordGraphqlPoints(7);
        meter.incrementGitHttpFetch();
        meter.incrementGitHttpPushBatch();
        meter.recordTransferBytes(1_024, 2_048, 512);

        SyncJob job = new SyncJob();
        job.setId(42L);
        meter.copyTo(job);

        assertEquals(1, job.getRestCallCount());
        assertEquals(1, job.getGraphqlCallCount());
        assertEquals(7, job.getGraphqlPointsUsed());
        assertEquals(1, job.getGitHttpFetchCount());
        assertEquals(1, job.getGitHttpPushBatchCount());
        assertEquals(1_024L, job.getGitReadBytes());
        assertEquals(2_048L, job.getGitWriteBytes());
        assertTrue(job.getRestCallsPerMinute() != null && job.getRestCallsPerMinute() >= 0);
        assertTrue(job.getGitPushPerMinute() != null && job.getGitPushPerMinute() >= 0);

        String seriesJson = job.getProviderTrafficSeriesJson();
        assertTrue(seriesJson != null && seriesJson.contains("\"samples\""));
        assertFalse(seriesJson.contains("restRemaining"));
        assertTrue(seriesJson.contains("\"rest\""));
        assertTrue(seriesJson.contains("\"graphql\""));
    }

    @Test
    void snapshotSeriesIsAMapForLiveWebSocket() throws Exception {
        meter.bindJob(7L);
        intercept("https://api.github.com/rate_limit");
        Map<String, Object> snap = meter.snapshotMap();
        assertNotNull(snap);
        assertEquals(1, snap.get("restCallCount"));
        assertTrue(snap.get("series") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> series = (Map<String, Object>) snap.get("series");
        assertTrue(series.get("samples") instanceof List<?>);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) series.get("samples");
        assertFalse(samples.isEmpty());
        assertFalse(samples.get(0).containsKey("restRemaining"));
    }

    @Test
    void unboundSnapshotIsNullSoUiDoesNotBroadcastZeros() {
        assertNull(meter.snapshotMap());
    }

    @Test
    void lfsBatchAndMediaHttpCountSeparatelyFromRest() throws Exception {
        meter.bindJob(9L);
        intercept("https://api.github.com/repos/acme/app/info/lfs/objects/batch");
        intercept("https://github.com/acme/app.git/info/lfs/objects/batch");
        intercept("https://media.githubusercontent.com/lfs-objects/abc");
        intercept("https://api.github.com/repos/acme/app/pulls");

        Map<String, Object> snap = meter.snapshotMap();
        assertNotNull(snap);
        assertEquals(1, snap.get("restCallCount"));
        assertEquals(2, snap.get("lfsApiCallCount"));
        assertEquals(1, snap.get("lfsTransferHttpCount"));
    }

    @Test
    void workerAttachAttributesHttpToSameJobMeter() throws Exception {
        meter.bindJob(11L);
        intercept("https://api.github.com/repos/acme/app/pulls/1");

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                meter.attachJob(11L);
                intercept("https://api.github.com/repos/acme/app/pulls");
            } catch (Exception e) {
                error.set(e);
            } finally {
                meter.detachJob();
                done.countDown();
            }
        });
        worker.start();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(error.get());

        SyncJob job = new SyncJob();
        job.setId(11L);
        meter.copyTo(job);
        assertEquals(2, job.getRestCallCount());
    }

    private void intercept(String uri) throws Exception {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create(uri));
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(HttpStatusCode.valueOf(200));
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        ClientHttpRequestExecution execution = (req, body) -> response;
        meter.intercept(request, new byte[0], execution);
    }
}

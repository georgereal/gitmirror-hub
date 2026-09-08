package com.gitutility.config;

import com.gitutility.service.ProviderRateMeter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScmRestTemplateFactoryTest {

    @Test
    void jdkHttpUrlConnectionRejectsPatch() {
        RestTemplate restTemplate = new RestTemplate(new SimpleClientHttpRequestFactory());
        Exception exception = assertThrows(Exception.class, () ->
                restTemplate.exchange(URI.create("http://127.0.0.1:1"), HttpMethod.PATCH,
                        new HttpEntity<>("{}"), String.class));
        assertTrue(rootMessage(exception).contains("Invalid HTTP method: PATCH"),
                () -> "expected PATCH rejection, got: " + rootMessage(exception));
    }

    @Test
    void sharedRestTemplateCanSendPatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> seenMethod = new AtomicReference<>();
        server.createContext("/repos/acme/app/pulls/1", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            RestTemplate restTemplate = new ScmRestTemplateFactory().restTemplate(passThroughMeter());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/repos/acme/app/pulls/1";
            restTemplate.exchange(URI.create(url), HttpMethod.PATCH,
                    new HttpEntity<>("{\"title\":\"x\"}", headers), String.class);
            assertEquals("PATCH", seenMethod.get());
        } finally {
            server.stop(0);
        }
    }

    private static ProviderRateMeter passThroughMeter() throws Exception {
        ProviderRateMeter meter = mock(ProviderRateMeter.class);
        when(meter.intercept(any(), any(), any())).thenAnswer(invocation -> {
            ClientHttpRequestExecution execution = invocation.getArgument(2);
            return execution.execute(invocation.getArgument(0), invocation.getArgument(1));
        });
        return meter;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        String last = String.valueOf(t.getMessage());
        while (cur != null) {
            if (cur.getMessage() != null) {
                last = cur.getMessage();
            }
            cur = cur.getCause();
        }
        return last;
    }
}

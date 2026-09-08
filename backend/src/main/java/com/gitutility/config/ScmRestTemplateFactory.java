package com.gitutility.config;

import com.gitutility.service.ProviderRateMeter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared SCM {@link RestTemplate} with a job-scoped rate/count interceptor.
 * <p>
 * Uses {@link JdkClientHttpRequestFactory} because Java {@code HttpURLConnection}
 * (Spring's default) rejects {@code PATCH} with {@code Invalid HTTP method: PATCH},
 * which GitHub/GHES require for PR title/body updates and close.
 */
@Configuration
public class ScmRestTemplateFactory {

    @Bean
    public RestTemplate restTemplate(ProviderRateMeter providerRateMeter) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.getInterceptors().add(providerRateMeter);
        return restTemplate;
    }
}

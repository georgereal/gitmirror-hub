package com.gitutility.config;

import com.gitutility.service.ProviderRateMeter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Shared SCM {@link RestTemplate} with a job-scoped rate/count interceptor.
 */
@Configuration
public class ScmRestTemplateFactory {

    @Bean
    public RestTemplate restTemplate(ProviderRateMeter providerRateMeter) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(providerRateMeter);
        return restTemplate;
    }
}

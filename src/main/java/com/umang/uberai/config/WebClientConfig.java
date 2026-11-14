package com.umang.uberai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A {@link WebClient} pointed at the Uber platform api-gateway. The base URL is configurable
 * (default {@code http://localhost:8080}); the {@code RideTools} use this client to call the real
 * Uber HTTP APIs. We treat that backend as an opaque external service.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient uberWebClient(@Value("${uber.base-url:http://localhost:8080}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}

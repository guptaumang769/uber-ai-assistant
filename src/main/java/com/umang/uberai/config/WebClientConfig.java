package com.umang.uberai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.umang.uberai.http.BackendClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient uberWebClient(@Value("${uber.base-url:http://localhost:8080}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    public BackendClient uberBackendClient(WebClient uberWebClient) {
        return new BackendClient(uberWebClient, "Uber");
    }
}

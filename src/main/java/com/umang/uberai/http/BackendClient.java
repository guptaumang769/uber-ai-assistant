package com.umang.uberai.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

public class BackendClient {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final WebClient webClient;
    private final String serviceName;

    public BackendClient(WebClient webClient, String serviceName) {
        this.webClient = webClient;
        this.serviceName = serviceName;
    }

    public JsonNode getJson(String path) {
        try {
            JsonNode envelope = webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(ex -> {
                        log.warn("[{}] GET {} failed: {}", serviceName, path, ex.toString());
                        return Mono.empty();
                    })
                    .block();
            return unwrap(envelope);
        } catch (Exception ex) {
            log.warn("[{}] GET {} threw: {}", serviceName, path, ex.toString());
            return null;
        }
    }

    public JsonNode postJson(String path, Object body) {
        try {
            JsonNode envelope = webClient.post()
                    .uri(path)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(ex -> {
                        log.warn("[{}] POST {} failed: {}", serviceName, path, ex.toString());
                        return Mono.empty();
                    })
                    .block();
            return unwrap(envelope);
        } catch (Exception ex) {
            log.warn("[{}] POST {} threw: {}", serviceName, path, ex.toString());
            return null;
        }
    }

    public String unavailableMessage(String action) {
        return "The " + serviceName + " backend is currently unavailable, so I couldn't " + action
                + ". Please make sure the backend is running and try again.";
    }

    private JsonNode unwrap(JsonNode envelope) {
        if (envelope == null || envelope.isNull()) {
            return null;
        }
        if (envelope.has("success") || envelope.has("data")) {
            if (envelope.has("success") && !envelope.path("success").asBoolean(true)) {
                log.warn("[{}] backend returned success=false, error={}", serviceName, envelope.path("error"));
                return null;
            }
            JsonNode data = envelope.get("data");
            return (data == null || data.isNull()) ? null : data;
        }
        return envelope;
    }
}

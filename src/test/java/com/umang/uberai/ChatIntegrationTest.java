package com.umang.uberai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.umang.uberai.web.ChatDtos.ChatRequest;
import com.umang.uberai.web.ChatDtos.ChatResponse;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ChatIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
    }

    @Test
    void health_returnsUp() {
        ResponseEntity<String> resp = rest.getForEntity("/api/v1/health", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("UP");
    }

    @Test
    void chat_greeting_returnsReply() {
        ChatRequest req = new ChatRequest("it-greet", "hello");
        ResponseEntity<ChatResponse> resp = rest.postForEntity("/api/v1/chat", req, ChatResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().conversationId()).isEqualTo("it-greet");
        assertThat(resp.getBody().reply()).isNotBlank();
    }

    @Test
    void chat_ragQuery_returnsFaqContent() {
        ChatRequest req = new ChatRequest("it-rag", "what is the cancellation policy?");
        ResponseEntity<ChatResponse> resp = rest.postForEntity("/api/v1/chat", req, ChatResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().reply()).containsIgnoringCase("cancel");
    }

    @Test
    void chat_blankMessage_returns400() {
        ChatRequest req = new ChatRequest("it-blank", "");
        ResponseEntity<String> resp = rest.postForEntity("/api/v1/chat", req, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    void chat_tooLongMessage_returns400() {
        String longMessage = "a".repeat(4001);
        ChatRequest req = new ChatRequest("it-long", longMessage);
        ResponseEntity<String> resp = rest.postForEntity("/api/v1/chat", req, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("VALIDATION_ERROR");
    }
}

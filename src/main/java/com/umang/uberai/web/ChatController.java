package com.umang.uberai.web;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.umang.uberai.web.ChatDtos.ChatRequest;
import com.umang.uberai.web.ChatDtos.ChatResponse;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient uberChatClient) {
        this.chatClient = uberChatClient;
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.conversationId());
        String reply = chatClient.prompt()
                .user(safe(request.message()))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return new ChatResponse(conversationId, reply);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @RequestParam(required = false) String conversationId,
            @RequestParam String message) {
        String cid = resolveConversationId(conversationId);
        Flux<String> header = Flux.just("[conversationId:" + cid + "]\n");
        Flux<String> content = chatClient.prompt()
                .user(safe(message))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .stream()
                .content();
        return Flux.concat(header, content);
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "uber-ai-assistant");
    }

    private String resolveConversationId(String provided) {
        return StringUtils.hasText(provided) ? provided : "default";
    }

    private String safe(String message) {
        return message == null ? "" : message;
    }
}

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

import reactor.core.publisher.Flux;

/**
 * HTTP entry point to the agent.
 *
 * <ul>
 *   <li>{@code POST /api/v1/chat} — one blocking turn: runs the message through the ChatClient
 *       (tools + RAG + memory) and returns the full reply.</li>
 *   <li>{@code GET /api/v1/chat/stream} — Server-Sent Events: streams the reply token-by-token so a UI
 *       can render it as it arrives.</li>
 *   <li>{@code GET /api/v1/health} — lightweight liveness probe (actuator also exposes /actuator/health).</li>
 * </ul>
 *
 * <p>The per-request {@code conversationId} is threaded into the {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor}
 * via the advisor param {@link ChatMemory#CONVERSATION_ID}, so each conversation keeps its own history.</p>
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*") // open CORS for the browser chat UI
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient uberChatClient) {
        this.chatClient = uberChatClient;
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String conversationId = resolveConversationId(request.conversationId());
        String reply = chatClient.prompt()
                .user(safe(request.message()))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return new ChatResponse(conversationId, reply);
    }

    /**
     * Streaming SSE. With a real LLM this streams true tokens as the model generates. With the mock
     * (which produces the full answer at once) Spring AI's default {@code stream(Prompt)} still emits
     * the answer as a Flux, so the UI sees it arrive as one or more SSE events.
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam(required = false) String conversationId,
                               @RequestParam String message) {
        String cid = resolveConversationId(conversationId);
        return chatClient.prompt()
                .user(safe(message))
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .stream()
                .content();
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

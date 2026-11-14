package com.umang.uberai.web;

/** Request/response payloads for the chat API. */
public final class ChatDtos {

    private ChatDtos() {
    }

    /** Chat request. {@code conversationId} scopes the memory; {@code message} is the user's turn. */
    public record ChatRequest(String conversationId, String message) {
    }

    /** Chat response: the assistant's reply, echoing the conversation id. */
    public record ChatResponse(String conversationId, String reply) {
    }
}

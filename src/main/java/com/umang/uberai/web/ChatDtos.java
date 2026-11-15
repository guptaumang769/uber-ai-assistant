package com.umang.uberai.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record ChatRequest(
            @Size(max = 100, message = "conversationId must be at most 100 characters")
            String conversationId,

            @NotBlank(message = "message must not be blank")
            @Size(max = 4000, message = "message must be at most 4000 characters")
            String message) {
    }

    public record ChatResponse(String conversationId, String reply) {
    }
}

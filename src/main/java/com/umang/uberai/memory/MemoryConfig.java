package com.umang.uberai.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CONVERSATION MEMORY.
 *
 * <p>An agent needs to remember earlier turns so follow-ups resolve: "book that ride" after "what's the
 * fare from 12.97,77.59 to 13.0,77.6" must know which coordinates. Spring AI 1.0.0 models this with
 * {@link ChatMemory}, keyed by a conversation id. We use {@link MessageWindowChatMemory} (keeps the last
 * N messages per conversation) backed by an {@link InMemoryChatMemoryRepository}. The
 * {@code MessageChatMemoryAdvisor} (wired in ChatClientConfig) reads/writes this store on every call
 * using the per-request conversation id, so each conversation stays isolated and stateful.</p>
 */
@Configuration
public class MemoryConfig {

    @Bean
    @ConditionalOnMissingBean(ChatMemory.class)
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}

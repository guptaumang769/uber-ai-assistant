package com.umang.uberai.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import com.github.benmanes.caffeine.cache.Cache;

public class CaffeineChatMemoryRepository implements ChatMemoryRepository {

    private final Cache<String, List<Message>> cache;

    public CaffeineChatMemoryRepository(Cache<String, List<Message>> cache) {
        this.cache = cache;
    }

    @Override
    public List<String> findConversationIds() {
        return new ArrayList<>(cache.asMap().keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Message> messages = cache.getIfPresent(conversationId);
        return messages != null ? new ArrayList<>(messages) : List.of();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        cache.put(conversationId, new ArrayList<>(messages));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        cache.invalidate(conversationId);
    }
}

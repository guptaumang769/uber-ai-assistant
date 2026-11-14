package com.umang.uberai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.umang.uberai.tools.RideTools;

/**
 * Registers the {@link MockUberChatModel} as the {@link ChatModel} — but ONLY when no real
 * ChatModel exists.
 *
 * <h2>The mock-vs-real switch</h2>
 * <ul>
 *   <li><b>Default (no key):</b> {@code application.yml} sets {@code spring.ai.model.chat=none}. Spring AI's
 *       Anthropic auto-configuration is {@code @ConditionalOnProperty(spring.ai.model.chat=anthropic)}, so
 *       with the value {@code none} it does NOT create a ChatModel. This bean's
 *       {@code @ConditionalOnMissingBean(ChatModel.class)} then fires and the mock is used — the app runs
 *       and answers end-to-end with no API key.</li>
 *   <li><b>Real LLM:</b> set {@code spring.ai.model.chat=anthropic} and {@code ANTHROPIC_API_KEY}. Now the
 *       Anthropic auto-config creates a real {@code AnthropicChatModel} bean, so
 *       {@code @ConditionalOnMissingBean} sees a ChatModel already present and this mock bean backs off.</li>
 * </ul>
 * Either way, {@code ChatClientConfig} builds the same ChatClient over whichever ChatModel bean exists.
 */
@Configuration
public class MockModelConfig {

    private static final Logger log = LoggerFactory.getLogger(MockModelConfig.class);

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public ChatModel mockUberChatModel(RideTools tools, VectorStore faqVectorStore) {
        log.info("No real ChatModel configured (spring.ai.model.chat != anthropic / no ANTHROPIC_API_KEY). "
                + "Using deterministic MockUberChatModel so the agent is demoable KEY-FREE. "
                + "Set spring.ai.model.chat=anthropic + ANTHROPIC_API_KEY to use a real LLM.");
        return new MockUberChatModel(tools, faqVectorStore);
    }
}

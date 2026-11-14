package com.umang.uberai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.umang.uberai.tools.RideTools;

/**
 * Assembles the {@link ChatClient} — the AGENT itself. This is where tools, RAG and memory come
 * together over whichever {@link ChatModel} is active (mock or real Anthropic).
 *
 * <p>Spring AI 1.0.0 wiring used here:</p>
 * <ul>
 *   <li>{@code ChatClient.builder(chatModel)} — start from the resolved ChatModel bean.</li>
 *   <li>{@code .defaultSystem(...)} — the Uber-assistant persona + a description of the tools, so a real
 *       LLM knows its role and when to call each tool.</li>
 *   <li>{@code .defaultTools(rideTools)} — register the {@code @Tool}-annotated methods as callable
 *       tools (TOOL CALLING). A real LLM decides when to invoke them; the mock invokes them directly.</li>
 *   <li>{@code .defaultAdvisors(memoryAdvisor, ragAdvisor)} — cross-cutting behaviors applied to every
 *       call: {@link MessageChatMemoryAdvisor} injects/persists conversation history (MEMORY), and
 *       {@link QuestionAnswerAdvisor} retrieves relevant FAQ docs from the vector store (RAG).</li>
 * </ul>
 */
@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT = """
            You are the Uber AI Assistant — a helpful, concise ride-hailing assistant.

            You can take real actions by calling these tools (use them; do not make up data):
            - estimateFare(pickupLat, pickupLng, dropLat, dropLng): estimate distance, surge and fare for a ride.
            - nearbyDrivers(lat, lng): find how many drivers are near a location and how close they are.
            - requestRide(riderId, pickupLat, pickupLng, dropLat, dropLng): request/book a ride for a rider.
            - rideStatus(rideId): look up an existing ride request's status.
            - tripStatus(tripId): look up an in-progress or completed trip's status.

            Guidance:
            - Coordinates are latitude/longitude pairs. Seeded drivers are around Bangalore (lat 12.97, lng 77.59).
            - Before booking, it's helpful to estimate the fare first so the user knows the price.
            - To request a ride you need a riderId and both pickup and drop coordinates.
            - Remember context across turns: if the user gave coordinates or a rider id earlier, reuse them.
            - After requesting a ride, give the user the ride id and its status.
            - For help/policy questions (cancellations, surge pricing, safety), answer from the provided
              reference documents when available.
            - If a tool reports the backend is unavailable, relay that plainly; do not invent results.
            Keep replies short and actionable.
            """;

    @Bean
    public ChatClient uberChatClient(ChatModel chatModel,
                                     RideTools rideTools,
                                     ChatMemory chatMemory,
                                     VectorStore faqVectorStore) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                // Tool calling: the model can invoke these methods.
                .defaultTools(rideTools)
                // Memory advisor (conversation history) + RAG advisor (FAQ retrieval).
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(faqVectorStore).build())
                .build();
    }
}

package com.umang.uberai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import com.umang.uberai.rag.MockEmbeddingModel;
import com.umang.uberai.tools.RideTools;

/**
 * Unit tests for the mock LLM's PLANNING: given a user message, does it route to the right tool /
 * RAG store? No network, no key.
 */
class MockUberChatModelTest {

    @Mock
    private RideTools tools;

    private VectorStore vectorStore;
    private MockUberChatModel model;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        // Real in-memory vector store with the key-free mock embedding model, seeded with one FAQ doc.
        vectorStore = SimpleVectorStore.builder(new MockEmbeddingModel()).build();
        vectorStore.add(List.of(new Document(
                "Cancellation policy: you can cancel a ride within 2 minutes of requesting for no cancellation fee.")));
        model = new MockUberChatModel(tools, vectorStore);
    }

    private String reply(Prompt prompt) {
        ChatResponse resp = model.call(prompt);
        return resp.getResult().getOutput().getText();
    }

    @Test
    void fareIntent_withCoords_invokesEstimateFare() {
        when(tools.estimateFare(12.97, 77.59, 13.0, 77.6))
                .thenReturn("Fare estimate: distance 4.2 km, surge 1.5x, fare ₹180.");

        String out = reply(new Prompt(new UserMessage("what's the fare from 12.97,77.59 to 13.0,77.6?")));

        verify(tools, times(1)).estimateFare(12.97, 77.59, 13.0, 77.6);
        assertThat(out).contains("180");
    }

    @Test
    void bookIntent_withCoordsAndRider_invokesRequestRide() {
        when(tools.requestRide(1L, 12.97, 77.59, 13.0, 77.6))
                .thenReturn("Ride requested. rideId=555, status=REQUESTED.");

        String out = reply(new Prompt(new UserMessage("book a ride from 12.97,77.59 to 13.0,77.6 as rider 1")));

        verify(tools).requestRide(1L, 12.97, 77.59, 13.0, 77.6);
        assertThat(out).contains("rideId=555");
    }

    @Test
    void nearbyIntent_withCoord_invokesNearbyDrivers() {
        when(tools.nearbyDrivers(12.97, 77.59)).thenReturn("2 driver(s) near 12.97,77.59");

        String out = reply(new Prompt(new UserMessage("find drivers near 12.97,77.59")));

        verify(tools).nearbyDrivers(12.97, 77.59);
        assertThat(out).contains("driver");
    }

    @Test
    void bookIntent_usesCoordsFromConversationMemory() {
        // Prior assistant turn (as injected by the memory advisor) echoed pickup/drop coords.
        when(tools.requestRide(1L, 12.97, 77.59, 13.0, 77.6))
                .thenReturn("Ride requested. rideId=999, status=REQUESTED.");

        List<Message> history = List.of(
                new UserMessage("what's the fare from 12.97,77.59 to 13.0,77.6?"),
                new AssistantMessage("Ride requested. rideId=1, status=REQUESTED "
                        + "(pickup 12.97,77.59 → drop 13.0,77.6). I'll match you with a nearby driver."),
                new UserMessage("ok book that ride"));
        String out = reply(new Prompt(history));

        // Even though the latest message has no coords, it recovers them from history.
        verify(tools).requestRide(1L, 12.97, 77.59, 13.0, 77.6);
        assertThat(out).contains("rideId=999");
    }

    @Test
    void rideStatusIntent_invokesRideStatus() {
        when(tools.rideStatus(3L)).thenReturn("Ride id=3 status=MATCHED");

        String out = reply(new Prompt(new UserMessage("what's the status of ride 3?")));

        verify(tools).rideStatus(3L);
        assertThat(out).contains("MATCHED");
    }

    @Test
    void tripIntent_invokesTripStatus() {
        when(tools.tripStatus(5L)).thenReturn("Trip id=5 status=IN_PROGRESS");

        String out = reply(new Prompt(new UserMessage("track trip 5")));

        verify(tools).tripStatus(5L);
        assertThat(out).contains("IN_PROGRESS");
    }

    @Test
    void helpIntent_answersFromRagStore() {
        String out = reply(new Prompt(new UserMessage("what is your cancellation policy?")));

        // Retrieved from the seeded FAQ doc, no tool call.
        assertThat(out.toLowerCase()).contains("cancel");
    }
}

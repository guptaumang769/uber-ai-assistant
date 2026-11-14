package com.umang.uberai.model;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.umang.uberai.tools.RideTools;

import reactor.core.publisher.Flux;

/**
 * A DETERMINISTIC, KEY-FREE stand-in for a real LLM.
 *
 * <h2>Why this exists</h2>
 * A real agent uses an LLM to PLAN: read the user's message, decide which tool(s) to call, observe the
 * results, then compose an answer. Without an API key there is no LLM to do that planning. This mock
 * plays the LLM's planning role with simple keyword/intent matching so the WHOLE agentic pipeline
 * (tool calling against the live Uber backend + RAG retrieval + conversation memory) is fully
 * demoable with {@code mvn spring-boot:run} and ZERO API key.
 *
 * <h2>What it does NOT do</h2>
 * It is not a language model — it does not generalize. It recognizes a handful of intents
 * (fare / nearby drivers / book ride / ride status / trip status / help) and INVOKES the corresponding
 * {@link RideTools} method or the RAG {@link VectorStore} directly, then formats a reply. It reads the
 * conversation history (injected by the chat-memory advisor into the {@link Prompt}) so multi-turn
 * context like "book that ride" after "what's the fare from 12.97,77.59 to 13.0,77.6" works: it
 * remembers the last coordinates it saw.
 *
 * <h2>How the real LLM replaces it</h2>
 * When {@code spring.ai.model.chat=anthropic} and {@code ANTHROPIC_API_KEY} are set, Spring AI's
 * Anthropic auto-configuration creates a real {@code AnthropicChatModel} bean. Our mock is declared
 * with {@code @ConditionalOnMissingBean(ChatModel.class)} (see MockModelConfig), so it silently backs
 * off and the real model does genuine planning against the SAME registered {@code @Tool} methods and
 * the SAME QuestionAnswerAdvisor RAG store. No other code changes.
 *
 * <p>Contract with Spring AI: a {@link ChatModel} must implement {@code call(Prompt)} returning a
 * {@link ChatResponse}. We read the latest user message from the prompt, route it, and wrap our reply
 * in {@code new ChatResponse(List.of(new Generation(new AssistantMessage(text))))}. The default
 * {@code stream(Prompt)} on the interface adapts this single response into a Flux, which the streaming
 * endpoint then chunks.</p>
 */
public class MockUberChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(MockUberChatModel.class);

    private final RideTools tools;
    private final VectorStore vectorStore;

    // A latitude,longitude pair, e.g. "12.97,77.59" or "12.97, 77.59".
    private static final Pattern COORD = Pattern.compile("(-?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)");
    private static final Pattern RIDER_ID = Pattern.compile("rider\\s*(?:id)?\\s*[#:=]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RIDE_ID = Pattern.compile("ride\\s*(?:id|request)?\\s*[#:=]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIP_ID = Pattern.compile("trip\\s*(?:id)?\\s*[#:=]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    // Coordinates the assistant previously echoed back, so a follow-up can reuse them from memory.
    private static final Pattern ANY_PICKUP = Pattern.compile("pickup\\s+(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_DROP = Pattern.compile("drop\\s+(-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    // Default demo coordinates near seeded Bangalore drivers, used when the user gives none.
    private static final double DEFAULT_LAT = 12.97;
    private static final double DEFAULT_LNG = 77.59;

    public MockUberChatModel(RideTools tools, VectorStore vectorStore) {
        this.tools = tools;
        this.vectorStore = vectorStore;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String userText = latestUserText(prompt);
        log.info("[mock-llm] planning for user text: {}", userText);
        String reply = route(userText, prompt.getInstructions());
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    /**
     * Streaming support. The interface's default {@code stream(Prompt)} throws
     * {@code UnsupportedOperationException}, so we override it: the mock produces its whole answer at
     * once, then we chunk it into word-sized pieces and emit them as a {@link Flux} of
     * {@link ChatResponse}s. This lets the SSE endpoint show tokens "arriving" even without a real
     * streaming LLM. A real Anthropic model streams genuine tokens here instead.
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String userText = latestUserText(prompt);
        log.info("[mock-llm] (stream) planning for user text: {}", userText);
        String reply = route(userText, prompt.getInstructions());
        String[] words = reply.split("(?<=\\s)");
        return Flux.fromArray(words)
                .map(chunk -> new ChatResponse(List.of(new Generation(new AssistantMessage(chunk)))));
    }

    /** The mock's "planner": pick an intent and invoke the matching tool / RAG store. */
    private String route(String text, List<Message> history) {
        String t = text == null ? "" : text.toLowerCase();

        // --- Trip tracking intent (checked before generic "status" so "track trip 5" wins). ---
        if (t.contains("trip") || (t.contains("track") && !t.contains("ride"))) {
            long tripId = firstLong(TRIP_ID, text, () -> -1L);
            if (tripId <= 0) {
                return "Which trip? Give me the trip id, e.g. 'track trip 5'.";
            }
            return tools.tripStatus(tripId);
        }

        // --- Ride status intent: "status of ride 3" / "track ride 3". ---
        if (t.contains("status") || t.contains("track")) {
            long rideId = firstLong(RIDE_ID, text, () -> -1L);
            if (rideId <= 0) {
                return "Which ride? Give me the ride id, e.g. 'status of ride 3'.";
            }
            return tools.rideStatus(rideId);
        }

        // --- Book / request a ride intent (check before the generic price/nearby branches). ---
        if (t.contains("book") || t.contains("request") || t.contains("get me a ride") || t.contains("need a ride")) {
            double[] pickup = pickupCoords(text, history);
            double[] drop = dropCoords(text, history);
            if (pickup == null || drop == null) {
                return "To book a ride I need pickup and drop coordinates, e.g. "
                        + "'book a ride from 12.97,77.59 to 13.0,77.6 as rider 1'.";
            }
            long riderId = firstLong(RIDER_ID, text, () -> 1L); // demo rider
            return tools.requestRide(riderId, pickup[0], pickup[1], drop[0], drop[1]);
        }

        // --- Fare estimate intent. ---
        if (t.contains("fare") || t.contains("estimate") || t.contains("price") || t.contains("cost")
                || t.contains("how much")) {
            double[] pickup = pickupCoords(text, history);
            double[] drop = dropCoords(text, history);
            if (pickup == null || drop == null) {
                return "To estimate a fare I need pickup and drop coordinates, e.g. "
                        + "'what's the fare from 12.97,77.59 to 13.0,77.6?'.";
            }
            return tools.estimateFare(pickup[0], pickup[1], drop[0], drop[1]);
        }

        // --- Nearby drivers intent. ---
        if (t.contains("driver") || t.contains("nearby") || t.contains("near me") || t.contains("available")) {
            double[] at = firstCoord(text);
            double lat = at != null ? at[0] : DEFAULT_LAT;
            double lng = at != null ? at[1] : DEFAULT_LNG;
            return tools.nearbyDrivers(lat, lng);
        }

        // --- Help / policy / FAQ intent -> RAG retrieval. ---
        if (isHelpQuestion(t)) {
            return answerFromDocs(text);
        }

        // --- Greeting / fallback. ---
        if (t.isBlank() || t.contains("hello") || t.contains("hi ") || t.equals("hi") || t.contains("help")) {
            return greeting();
        }

        // Last resort: try RAG, since unknown questions are often policy/help questions.
        String rag = answerFromDocs(text);
        if (rag != null && !rag.isBlank()) {
            return rag;
        }
        return greeting();
    }

    private boolean isHelpQuestion(String t) {
        return t.contains("cancel") || t.contains("surge") || t.contains("safety") || t.contains("safe")
                || t.contains("policy") || t.contains("refund") || t.contains("charge")
                || t.contains("how do i") || t.contains("can i") || t.contains("what is") || t.contains("faq")
                || t.contains("share") || t.contains("emergency") || t.contains("sos");
    }

    /**
     * RAG in action (key-free): embed the question, run a similarity search over the seeded FAQ/policy
     * docs, and return the best-matching passages. The real LLM path uses the same VectorStore via the
     * QuestionAnswerAdvisor, which stuffs these retrieved docs into the prompt for a synthesized answer.
     */
    private String answerFromDocs(String question) {
        try {
            List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder().query(question).topK(2).build());
            if (hits == null || hits.isEmpty()) {
                return "I don't have a policy document that covers that yet.";
            }
            StringBuilder sb = new StringBuilder("Here's what our help docs say:\n\n");
            for (Document d : hits) {
                sb.append(d.getText().trim()).append("\n\n");
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            log.warn("[mock-llm] RAG lookup failed: {}", ex.toString());
            return "I couldn't retrieve the help docs right now.";
        }
    }

    /**
     * Resolve pickup coordinates: the FIRST coordinate pair in the message, else the last pickup we
     * echoed in history (via the "from A to B" pattern), else null.
     */
    private double[] pickupCoords(String text, List<Message> history) {
        double[][] pair = fromToCoords(text);
        if (pair != null) {
            return pair[0];
        }
        double[] single = firstCoord(text);
        if (single != null) {
            return single;
        }
        return lastCoordFromHistory(history, ANY_PICKUP);
    }

    /**
     * Resolve drop coordinates: the SECOND coordinate pair in the message, else the last drop we echoed
     * in history, else null.
     */
    private double[] dropCoords(String text, List<Message> history) {
        double[][] pair = fromToCoords(text);
        if (pair != null) {
            return pair[1];
        }
        return lastCoordFromHistory(history, ANY_DROP);
    }

    /** Extract exactly two coordinate pairs (pickup + drop) from the message, or null if fewer than two. */
    private double[][] fromToCoords(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = COORD.matcher(text);
        double[] first = null;
        double[] second = null;
        if (m.find()) {
            first = new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        }
        if (m.find()) {
            second = new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        }
        return (first != null && second != null) ? new double[][]{first, second} : null;
    }

    /** Extract the first coordinate pair in the message, or null. */
    private double[] firstCoord(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = COORD.matcher(text);
        if (m.find()) {
            return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        }
        return null;
    }

    /** Scan the conversation history (injected by the memory advisor) for the most recent echoed coordinate. */
    private double[] lastCoordFromHistory(List<Message> history, Pattern pattern) {
        if (history == null) {
            return null;
        }
        double[] found = null;
        for (Message msg : history) {
            String txt = msg.getText();
            if (txt == null) {
                continue;
            }
            Matcher m = pattern.matcher(txt);
            while (m.find()) {
                found = new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))}; // keep last
            }
        }
        return found;
    }

    private long firstLong(Pattern p, String text, java.util.function.Supplier<Long> fallback) {
        Matcher m = p.matcher(text == null ? "" : text);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return fallback.get();
    }

    // The QuestionAnswerAdvisor rewrites the current USER message by APPENDING retrieved-doc context
    // after this marker (its default template). For a real LLM that context is helpful; for our
    // keyword router it is noise (the FAQ text mentions "cancel"/"surge"/etc.), so we strip it to
    // recover the user's ORIGINAL query before routing. Real LLMs read the full augmented prompt.
    private static final String QA_CONTEXT_MARKER = "Context information is below";

    private String latestUserText(Prompt prompt) {
        // The memory advisor injects prior turns; the newest USER message is the current request.
        List<Message> instructions = prompt.getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message m = instructions.get(i);
            if (m.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
                return stripRagContext(m.getText());
            }
        }
        return stripRagContext(prompt.getContents());
    }

    private String stripRagContext(String text) {
        if (text == null) {
            return "";
        }
        int idx = text.indexOf(QA_CONTEXT_MARKER);
        return (idx >= 0 ? text.substring(0, idx) : text).trim();
    }

    private String greeting() {
        return "Hi! I'm your Uber AI assistant. I can:\n"
                + "- estimate a fare (\"what's the fare from 12.97,77.59 to 13.0,77.6?\")\n"
                + "- find nearby drivers (\"find drivers near 12.97,77.59\")\n"
                + "- book a ride (\"book a ride from 12.97,77.59 to 13.0,77.6 as rider 1\")\n"
                + "- check a ride (\"status of ride 3\")\n"
                + "- track a trip (\"track trip 5\")\n"
                + "- answer help questions on cancellations, surge pricing and safety.";
    }
}

# Uber AI Assistant

An **agentic AI assistant** over the Uber platform backend, built with **Spring AI 1.0.0** on
Spring Boot 3.3.5 / Java 21.

Talk to it in natural language — *"what's the fare to the airport? book me a ride from 12.97,77.59 to
13.0,77.6 as rider 1"* — and it **plans, calls real backend APIs as tools, answers help questions from
a knowledge base (RAG), and remembers the conversation**. It runs with **zero API key** out of the box
using a deterministic mock model, and switches to a real LLM (Anthropic) when you provide a key — no
code changes.

---

## What is "agentic AI"?

A plain LLM only produces text. An **agent** runs a loop:

```
        ┌─────────────────────────────┐
        │  PLAN   — decide next step   │
        │  ACT    — call a tool/API    │
        │  OBSERVE — read the result   │
        └───────────┬─────────────────┘
                    │  repeat until it can answer
                    ▼
              FINAL ANSWER
```

Given *"book a ride to the airport"* the agent plans: it can estimate the fare first → calls
`estimateFare`, observes the price, then calls `requestRide`, and finally tells you the ride id. The
**LLM decides which tools to call and when** — that decision-making is what makes it an agent rather
than a chatbot.

---

## Architecture

```
Browser / curl
      │  POST /api/v1/chat   |   GET /api/v1/chat/stream (SSE)
      ▼
ChatController ──► ChatClient (the agent)  ── Spring AI 1.0.0
                     │
     ┌───────────────┼────────────────────────────────┐
     │               │                                 │
 @Tool methods   RAG advisor                     ChatMemory advisor
 (RideTools)     (QuestionAnswerAdvisor)         (MessageChatMemoryAdvisor)
     │               │                                 │
     ▼               ▼                                 ▼
 Uber platform   SimpleVectorStore                MessageWindowChatMemory
 HTTP APIs       (FAQ/policy docs)                (per conversationId)
 (WebClient)
                     ▲
                     │  runs over
             ┌───────┴─────────┐
             │   ChatModel      │
             │  mock  OR  real  │
             └──────────────────┘
```

See **[DIAGRAMS.md](DIAGRAMS.md)** for the full HLD flowchart and a sequence diagram of one agentic turn.

**Pieces:**

- **`ChatClient`** (`config/ChatClientConfig`) — the agent. Built from the active `ChatModel` with a
  system prompt, the tool beans, and two advisors (RAG + memory).
- **Tools** (`tools/RideTools`) — five `@Tool`-annotated methods that call the real Uber platform HTTP
  APIs via a `WebClient` and unwrap the `ApiResponse<T>` envelope. Backend down → a friendly "backend
  unavailable" string, never an exception.
- **RAG** (`rag/`) — FAQ/policy markdown (`resources/docs/`) is embedded into an in-memory
  `SimpleVectorStore` at startup; a `QuestionAnswerAdvisor` retrieves relevant passages for help
  questions. A tiny hash-based `MockEmbeddingModel` keeps this key-free.
- **Memory** (`memory/MemoryConfig`) — `MessageWindowChatMemory` backed by a Caffeine cache (max 1000
  conversations, 30-min TTL), keyed by `conversationId`, so follow-ups ("book that ride" after
  "what's the fare from A to B") keep context without leaking memory.
- **Web** (`web/ChatController`) — blocking chat, streaming SSE, health; CORS open for a UI.

---

## The mock-vs-real-LLM design (key-free by default)

There is no LLM without an API key, so the whole pipeline would be undemoable. To fix that:

- **Default (no key):** `application.yml` sets `spring.ai.model.chat=none`. Spring AI's Anthropic
  auto-configuration is gated on `spring.ai.model.chat=anthropic`, so with `none` **no real ChatModel
  bean is created**. Our `MockUberChatModel` — declared `@ConditionalOnMissingBean(ChatModel.class)` —
  then activates. It plays the LLM's *planning* role with deterministic keyword→intent matching: it
  recognizes fare / nearby drivers / book ride / ride status / trip status / help and **actually
  invokes the matching tool or the RAG store**, then formats a reply. So `mvn spring-boot:run` gives a
  fully working agent with **zero keys**.

- **Real LLM:** set `SPRING_AI_MODEL_CHAT=anthropic` and `ANTHROPIC_API_KEY`. Spring AI auto-configures
  a real `AnthropicChatModel`; because a `ChatModel` now exists, the mock **backs off automatically**.
  The real model does genuine planning against the **same** `@Tool` methods and the **same** RAG store.
  No other code changes.

The mock is not a language model — it does not generalize. It exists purely so the end-to-end
tool-calling + RAG + memory flow is demoable offline.

---

## Run it — key-free

**Prerequisites:** JDK 21 (Temurin or Corretto) · Maven. No API key needed — it runs on a
built-in mock model by default (see [Enable a real LLM](#enable-a-real-llm) to switch).

```bash
git clone https://github.com/guptaumang769/uber-ai-assistant.git
cd uber-ai-assistant

JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn spring-boot:run
# starts on http://localhost:8091
```

On Windows (PowerShell): `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21"; mvn spring-boot:run`.

Try each capability (the mock routes each to the right tool / RAG store):

```bash
# Fare    → estimateFare tool
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","message":"what is the fare from 12.97,77.59 to 13.0,77.6?"}'

# Drivers → nearbyDrivers tool
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","message":"find drivers near 12.97,77.59"}'

# Book    → requestRide tool
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","message":"book a ride from 12.97,77.59 to 13.0,77.6 as rider 1"}'

# Ride    → rideStatus tool
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","message":"status of ride 3"}'

# Trip    → tripStatus tool
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","message":"track trip 5"}'

# Help    → RAG retrieval from FAQ docs (no backend needed)
curl -s -X POST http://localhost:8091/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo","message":"what is your cancellation policy?"}'

# Streaming (Server-Sent Events, token-by-token)
curl -N "http://localhost:8091/api/v1/chat/stream?conversationId=demo&message=find%20drivers%20near%2012.97,77.59"

# Health
curl -s http://localhost:8091/api/v1/health
curl -s http://localhost:8091/actuator/health
```

If the Uber platform gateway is not running, the tool calls return a friendly *"backend unavailable"*
message; RAG/help questions still work fully offline.

---

## Enable a real LLM

macOS/Linux:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export SPRING_AI_MODEL_CHAT=anthropic
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn spring-boot:run
```

Windows (PowerShell):

```powershell
$env:ANTHROPIC_API_KEY="sk-ant-..."
$env:SPRING_AI_MODEL_CHAT="anthropic"
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21"
mvn spring-boot:run
```

Now Claude does the planning: it reads the `@Tool` descriptions, decides which tools to call, observes
results, and composes answers grounded in the RAG docs. The default model is
`claude-3-5-sonnet-latest` (see `application.yml`).

---

## Live tool calls: integrating with uber-platform

The tools call these real endpoints (base URL `${UBER_BASE_URL:http://localhost:8080}`, all via the
api-gateway):

| Tool            | HTTP call                                                            |
|-----------------|---------------------------------------------------------------------|
| `estimateFare`  | `GET  /api/v1/pricing/estimate?pickupLat=&pickupLng=&dropLat=&dropLng=` |
| `nearbyDrivers` | `GET  /api/v1/locations/nearby?lat=&lng=&radiusKm=`                  |
| `requestRide`   | `POST /api/v1/rides`                                                 |
| `rideStatus`    | `GET  /api/v1/rides/{rideId}`                                        |
| `tripStatus`    | `GET  /api/v1/trips/{tripId}`                                        |

For **live** tool calls, start the `uber-platform` api-gateway on `:8080` first, then run this
assistant. The backend is treated as an opaque external HTTP service (no uber-platform classes are
imported); all responses are the `ApiResponse<T> { success, data, error }` envelope, unwrapped in
`RideTools`. Seeded drivers are around Bangalore (lat 12.97, lng 77.59), so use nearby coordinates.

Override the base URL with `UBER_BASE_URL` if the gateway runs elsewhere.

---

## Docker

Multi-stage [Dockerfile](Dockerfile) (Maven build → slim JRE). Runs key-free with the
mock model; pass a key at runtime to switch to a real LLM.

```bash
docker build -t uber-ai-assistant .

# Key-free (mock model):
docker run -p 8091:8091 uber-ai-assistant

# Real LLM + live tool calls (uber-platform gateway reachable on the same network):
docker run -p 8091:8091 \
  -e SPRING_AI_MODEL_CHAT=anthropic \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e UBER_BASE_URL=http://api-gateway:8080 \
  uber-ai-assistant
```

---

## Tests

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn test
```

**Unit tests** (no network, no key):
- `RideToolsTest` — a real `WebClient` on a stub `ExchangeFunction` asserts each tool hits the
  right method + path (incl. the pricing query params) and unwraps the envelope, plus graceful
  degradation when the backend is down.
- `MockUberChatModelTest` — asserts the mock's planner routes each intent to the correct tool
  (incl. recovering pickup/drop coordinates from conversation memory) and answers help questions
  from the RAG store.

**Integration tests** (`ChatIntegrationTest`, `@SpringBootTest`):
- Context loads with all beans wired (mock model, tools, RAG, Caffeine memory).
- Health endpoint returns UP.
- Chat endpoint returns a reply with the correct conversationId.
- RAG query retrieves FAQ content.
- Validation rejects blank and oversized messages with a structured error response.

---

## Known Limitations

**Mock model routing** — The key-free `MockUberChatModel` uses keyword matching, not natural
language understanding. It recognizes a fixed set of intents (fare / book / nearby / status / help).
Ambiguous inputs like *"I booked a flight"* will misroute to the ride-booking tool. This is
intentional — the mock exists only to demo the tool-calling, RAG, and memory pipeline offline.
Switch to a real LLM (`SPRING_AI_MODEL_CHAT=anthropic`) for genuine natural language planning; the
tool/RAG/memory pipeline is identical, only the planning engine changes.

**Memory** — Conversation memory is bounded by a Caffeine cache (max 1000 conversations, 30-minute
idle TTL). This is sufficient for demo and interview purposes; a production deployment would use a
persistent store (Redis, database).

**Single-user** — No authentication or multi-tenant isolation. The `conversationId` is caller-supplied
with no ownership check.

---

## Spring AI 1.0.0 APIs used

- Tools: `@org.springframework.ai.tool.annotation.Tool`, `@ToolParam`; registered via
  `ChatClient.Builder.defaultTools(Object...)`.
- Agent: `ChatClient.builder(chatModel).defaultSystem(...).defaultTools(...).defaultAdvisors(...).build()`;
  per-request `.prompt().user(...).advisors(a -> a.param(ChatMemory.CONVERSATION_ID, id)).call().content()`
  and `.stream().content()`.
- Memory: `MessageWindowChatMemory.builder().chatMemoryRepository(caffeineChatMemoryRepository).maxMessages(n).build()`
  + `MessageChatMemoryAdvisor.builder(chatMemory).build()`. Caffeine cache limits to 1000 conversations with
  30-min idle TTL.
- RAG: `SimpleVectorStore.builder(embeddingModel).build()` +
  `QuestionAnswerAdvisor.builder(vectorStore).build()`.
- Mock model: implements `ChatModel.call(Prompt)` and `stream(Prompt)`, returning
  `new ChatResponse(List.of(new Generation(new AssistantMessage(text))))`.
- Backoff: `@ConditionalOnMissingBean(ChatModel.class)` + `spring.ai.model.chat` gate.

---

## Roadmap

This is the **second** of a planned set of agentic AI projects over these portfolio backends:
- ✅ BookMyShow AI Concierge — tools + RAG + memory over a movie-booking backend.
- ✅ Uber AI Assistant (this project) — a ride-hailing toolset over the uber-platform gateway.
- ⏭ MCP server — expose these tools over the Model Context Protocol so any MCP client can use them.

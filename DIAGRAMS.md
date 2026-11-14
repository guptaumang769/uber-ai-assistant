# Diagrams

Architecture and flow diagrams for the Uber AI Assistant.

## 1. High-Level Design (HLD)

How a chat request flows through the agent: the `ChatClient` orchestrates tools (which call the
Uber platform APIs), the RAG vector store, conversation memory, and the underlying `ChatModel`
(mock or real).

```mermaid
flowchart TD
    UI["Browser chat UI / curl"]

    subgraph App["uber-ai-assistant (Spring Boot, :8091)"]
        CTRL["ChatController<br/>POST /api/v1/chat<br/>GET /api/v1/chat/stream (SSE)"]
        CC["ChatClient (the agent)<br/>system prompt + tools + advisors"]

        subgraph Tools["Tool calling"]
            RT["RideTools<br/>@Tool methods"]
        end

        subgraph Rag["RAG"]
            QA["QuestionAnswerAdvisor"]
            VS["SimpleVectorStore<br/>FAQ / policy docs"]
            EM["EmbeddingModel<br/>(mock hash-based, key-free)"]
        end

        subgraph Mem["Memory"]
            MA["MessageChatMemoryAdvisor"]
            CM["MessageWindowChatMemory<br/>keyed by conversationId"]
        end

        subgraph Model["ChatModel (planner)"]
            MOCK["MockUberChatModel<br/>default, key-free"]
            REAL["AnthropicChatModel<br/>when key + profile set"]
        end
    end

    GW["Uber platform api-gateway<br/>/api/v1 (:8080)"]

    UI -->|"conversationId + message"| CTRL
    CTRL --> CC
    CC --> RT
    CC --> QA
    QA --> VS
    VS --> EM
    CC --> MA
    MA --> CM
    CC --> MOCK
    CC -.->|"key + spring.ai.model.chat=anthropic"| REAL
    RT -->|"WebClient (HTTP)"| GW
    CC -->|"reply / SSE tokens"| UI
```

## 2. One agentic turn (sequence)

A single request — *"book a ride to the airport"* — showing the plan → act → observe loop: the LLM
plans, calls `estimateFare`, observes, then calls `requestRide`, and returns a final answer. (Shown
with the real LLM doing the planning; the mock follows the same tool-calling path via deterministic
intent matching.)

```mermaid
sequenceDiagram
    actor User
    participant Ctrl as ChatController
    participant Agent as ChatClient
    participant Mem as ChatMemory
    participant RAG as VectorStore
    participant LLM as ChatModel
    participant Tools as RideTools
    participant GW as Uber api-gateway

    User->>Ctrl: POST /chat {conversationId, "book a ride to the airport"}
    Ctrl->>Agent: prompt().user(msg).advisors(conversationId)

    Agent->>Mem: load prior turns for conversationId
    Mem-->>Agent: history (e.g. earlier pickup/drop coords)
    Agent->>RAG: retrieve relevant help docs
    RAG-->>Agent: top-k FAQ passages

    Agent->>LLM: system + history + docs + user message + tool schemas
    Note over LLM: PLAN — estimate the fare first
    LLM-->>Agent: tool call: estimateFare(pickup, drop)
    Agent->>Tools: estimateFare(12.97, 77.59, 13.0, 77.6)
    Tools->>GW: GET /api/v1/pricing/estimate?...
    GW-->>Tools: ApiResponse{ distanceKm, surge, fare }
    Tools-->>Agent: "distance 4.2 km, surge 1.5x, fare ₹180"
    Agent->>LLM: OBSERVE — tool result

    Note over LLM: PLAN — request the ride
    LLM-->>Agent: tool call: requestRide(riderId, pickup, drop)
    Agent->>Tools: requestRide(1, 12.97, 77.59, 13.0, 77.6)
    Tools->>GW: POST /api/v1/rides
    GW-->>Tools: ApiResponse{ rideId, REQUESTED }
    Tools-->>Agent: "Ride requested, id=555"
    Agent->>LLM: OBSERVE — tool result

    Note over LLM: enough to answer
    LLM-->>Agent: final answer
    Agent->>Mem: save this turn for conversationId
    Agent-->>Ctrl: "Ride requested, id 555 — matching a driver"
    Ctrl-->>User: reply
```

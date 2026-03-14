# finSense Architectural Context

## Service Purpose and Business Goals
- Build an event-driven financial assistant platform that:
- Ingests financial transactions.
- Classifies each transaction using a hybrid AI strategy (fast ML first, LLM fallback for low-confidence cases).
- Produces personalized user recommendations.
- Delivers recommendation notifications to users.
- Keeps REST flows responsive by moving heavy/slow AI work to asynchronous processing.

## Core Responsibilities
- Transaction intake and lifecycle management.
- Synchronous ML transaction classification.
- Asynchronous LLM-based transaction classification fallback.
- Recommendation request orchestration and status tracking.
- AI-based recommendation generation using tool-assisted (ReAct-style) reasoning.
- User notification delivery (Telegram).
- Event publication/consumption via Kafka with at-least-once processing.
- Persistence of business state and AI metadata in PostgreSQL.
- End-to-end traceability using correlation identifiers (`transactionId`, `requestId`).

## Architectural Style and Key Constraints
- Distributed event-driven microservice architecture.
- Stateless services; no shared memory.
- Kafka is the persistent event log and async backbone.
- Shared PostgreSQL instance for MVP, separated by schemas (`core`, `recommendations`).
- Fixed stack: Kotlin/Java, Spring Boot, Kafka, PostgreSQL, Docker, REST/JSON, external LLM APIs, JVM-compatible ML libs.
- Local development with synthetic data; deployable to VPS.

## Architecture Layers and Components

### API Layer
- `Core Service`
- Accepts user/API requests.
- Consumes `raw-transactions`.
- Calls `Classifier Service` synchronously over REST.
- Publishes async requests to Kafka (`llm-classifier-requests`, `coach-requests`).
- Consumes `llm-classifier-responses`.
- Persists transaction and recommendation state.
- `Notify Service`
- Consumes `coach-responses` from Kafka.
- Sends outbound notifications via Telegram Bot API.

### ML Layer
- `Classifier Service`
- Synchronous REST classifier (`/api/classify`).
- Returns `category` + `confidence` quickly for real-time path.

### AI Layer
- `Transaction Classifier Agent`
- Consumes `llm-classifier-requests`.
- Calls external LLM provider.
- Produces `llm-classifier-responses`.
- `Financial Coach Agent`
- Consumes `coach-requests`.
- Reads user transactions/recommendation records from DB.
- Uses deterministic tools (aggregations/functions) + LLM to generate advice (ReAct approach).
- Updates recommendation record.
- Produces `coach-responses`.

### Infrastructure
- `Kafka` (+ Zookeeper in deployment diagram).
- `PostgreSQL` with `core` and `recommendations` schemas.
- Docker Compose split:
- Infrastructure: `docker-compose.infrastructure.yml`.
- Services: `docker-compose.services.yml`.

## Data Flow Overview

### 1) Transaction Classification (Hybrid ML + LLM)
1. Transaction generator publishes `raw-transaction` to Kafka (`raw-transactions` topic).
2. `Core Service` consumes transaction and calls `Classifier Service` via REST.
3. If `confidence >= threshold` (diagram uses `0.9`): persist ML result directly.
4. If `confidence < threshold`: publish `llm-classifier-request` (`transactionId`, `requestId`) to Kafka.
5. `Transaction Classifier Agent` consumes request, calls LLM provider, publishes `llm-classifier-response` (`category`, `requestId`).
6. `Core Service` consumes response and persists final classification from LLM.

### 2) Recommendation Generation (Manual User Request)
1. User calls `POST /users/{id}/recommendations`.
2. `Core Service` creates `requestId` (UUID), inserts recommendation with `status=PENDING`.
3. `Core Service` publishes `coach-request` (`requestId`, `userId`, params) and returns `202 Accepted`.
4. `Financial Coach Agent` consumes request, loads transactions, runs tools/aggregations, calls LLM.
5. Agent updates recommendation as `COMPLETED` with `advice_data` and `completed_at`.
6. Agent publishes `coach-response` (`requestId`, `userId`, summary, advice).
7. `Notify Service` consumes `coach-response` and sends Telegram message.
8. User polls `GET /recommendations/{requestId}`; `Core Service` returns status + recommendation payload.

## Transaction State Machine
- `NEW` -> `ML_CLASSIFYING` when raw transaction is consumed.
- `ML_CLASSIFYING` -> `CLASSIFIED` if ML confidence above threshold.
- `ML_CLASSIFYING` -> `LLM_CLASSIFYING` if confidence below threshold.
- `ML_CLASSIFYING` -> `RETRYING` on ML error.
- `LLM_CLASSIFYING` -> `CLASSIFIED` on valid LLM response.
- `LLM_CLASSIFYING` -> `RETRYING` on LLM processing error.
- `LLM_CLASSIFYING` -> `FAILED` on LLM timeout.
- `RETRYING` -> `ML_CLASSIFYING` or `LLM_CLASSIFYING` on retry; -> `FAILED` when max retries exceeded.

## Key Domain Entities

### `core.users`
- `id (uuid, PK)`
- `email (string, UK)`
- `created_at (timestamp)`

### `core.accounts`
- `id (uuid, PK)`
- `user_id (uuid, FK -> users)`
- `number (string, UK)`
- `type (string)`
- `currency (string)`
- `created_at (timestamp)`

### `core.transactions`
- `id (uuid, PK)`
- `account_id (uuid, FK -> accounts)`
- `user_id (uuid, FK -> users)`
- `amount (decimal)`
- `description (text)`
- `merchant_name (string)`
- `mcc_code (string)`
- `transaction_date (timestamp)`
- `status (string)`
- `category (string)`
- `classifier_source (string)` (ML or LLM origin)
- `classifier_confidence (real)`
- `classified_at (timestamp)`

### `recommendations.recommendations`
- `id (uuid, PK)` (used as recommendation requestId in sequence)
- `user_id (uuid, FK -> users)`
- `created_at (timestamp)`
- `status (string)` (`PENDING`/`COMPLETED`, with error path support)
- `advice_data (jsonb)`
- `request_params (jsonb)`
- `completed_at (timestamp)`
- `error (text)`

## External Integrations
- `LLM Provider` (e.g., OpenAI/DeepSeek in diagrams):
- Used by `Transaction Classifier Agent` and `Financial Coach Agent` via HTTPS.
- `Telegram Bot API`:
- Used by `Notify Service` to push recommendation notifications.
- `Transaction Generator`:
- External producer of `raw-transactions` Kafka events.
- User/client applications:
- Call REST endpoints on `Core Service`.

## Messaging and Reliability Rules
- Kafka topics shown in architecture:
- `raw-transactions`
- `llm-classifier-requests`
- `llm-classifier-responses`
- `coach-requests`
- `coach-responses`
- Delivery model: at-least-once.
- Handlers must be idempotent.
- Use retries and explicit failure states.
- Preserve correlation IDs across all events/logs (`transactionId`, `requestId`).

## Non-Functional Targets
- ML classification latency: `< 50 ms`.
- End-to-end transaction processing (p95): `< 2 s`.
- No blocking LLM calls in REST request path.
- Observe and measure:
- Transaction lifecycle traceability.
- AI decision logs.
- Latency and error-rate metrics.
- LLM usage and ML->LLM fallback ratio.

## Architectural Tradeoffs (Current MVP)
- Shared database (single Postgres, multiple schemas) chosen over database-per-service for speed of MVP delivery.
- REST for fast ML classification; Kafka for slower LLM flows.
- Structured relational tables for core transactional data; JSONB for AI outputs/metadata.
- Design must allow future evolution: database-per-service, DLQ/advanced retries, distributed tracing, Redis cache, local LLM inference.

## Repository Structure
- Main services folder/file structure is maintained in `./.ai/STRUCTURE.md`.

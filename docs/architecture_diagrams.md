## System Context Diagram


```mermaid
graph TB
    subgraph "Внешние системы"
        LLM[LLM Provider<br>OpenAI/YandexGPT]
        TG[Telegram Bot<br>Уведомления]
    end

    subgraph "Пользователи"
        User[ Пользователь<br>Клиент банка]
        Gen[ Генератор<br>Тестовые транзакции]
    end

    System[ FinSense System<br>Гибридная AI-система]

    User -->|REST API<br>аналитика/советы| System
    Gen -->|raw-transactions| System
    System -->|LLM вызовы| LLM
    System -->|coach-responses| TG

```

## Kafka topics
- raw-transactions	Generator -> Core	Сырые транзакции
- llm-classifier-requests	Core ->	Reasoning Agent	Транзакции для LLM
- llm-classifier-responses	Reasoning Agent ->	Core	Результаты от LLM
- coach-requests	Core (scheduler + REST handler) -> Coach Agent	Запросы на генерацию советов (по расписанию или вручную)
- coach-responses	Coach -> Notify Service (опционально)	Готовые советы

## Logical Architecture
```mermaid
graph TD
    subgraph "Внешние системы"
        LLM[LLM Provider]
        TG[Telegram Bot]
    end

    subgraph "Пользователи"
        User[Пользователь]
        Gen[Генератор транзакций]
    end

    subgraph "FinSense System"
        subgraph "API Layer"
            Core[Core Service]
            Notify[Notify Service]
        end
        
        subgraph "ML Layer"
            Classifier[Classifier Service]
        end
        
        subgraph "AI Layer"
            Reasoning[Transaction Classifier Agent]
            Coach[Financial Coach Agent]
        end
        
        subgraph "Infrastructure"
            Kafka[(Kafka)]
            DB[(Postgres<br>-core schema<br>-reccomendations schema)]
        end
    end

    User -->|REST / get advice| Core
    Gen -->|raw-transactions| Kafka
    
    Core -->|REST / classify| Classifier
    Core -->|llm‑classifier‑requests| Kafka
    Core -->| coach‑requests| Kafka
    Core -->|CRUD| DB
    Kafka -->|llm‑classifier‑responses| Core
    Kafka -->|raw-transactions | Core
    
    Reasoning -->| llm‑classifier‑responses| Kafka
    Reasoning -->|LLM calls| LLM
    Kafka -->|llm‑classifier‑requests| Reasoning
    
    Coach -->|LLM+tools| LLM
    Coach <-->|write advices/reading transactions| DB
    Coach -->|coach responses| Kafka
    Kafka -->|coach requests| Coach

    Notify -->|REST / send notification| TG
    Kafka -->|coach responses| Notify
```

## DB Scheme

```mermaid
erDiagram
    CORE__USERS {
        uuid id PK
        string email UK
        timestamp created_at
    }

    CORE__ACCOUNTS {
        uuid id PK
        uuid user_id FK
        string account_number UK
        string account_type
        string currency
        timestamp created_at
    }

    CORE__TRANSACTIONS {
        uuid id PK
        uuid account_id FK
        uuid user_id FK
        decimal amount
        text description
        string merchant_name
        string mcc_code
        timestamp transaction_date
        string status
        string category
        string classifier_source
        real classifier_confidence
        timestamp classified_at
    }

    RECOMMENDATIONS__RECOMMENDATIONS {
        uuid id PK
        uuid user_id FK
        timestamp created_at
        json advice_data
    }

    CORE__USERS ||--o{ CORE__ACCOUNTS : ""
    CORE__USERS ||--o{ CORE__TRANSACTIONS : ""
    CORE__ACCOUNTS ||--o{ CORE__TRANSACTIONS : ""
    CORE__USERS ||--o{ RECOMMENDATIONS__RECOMMENDATIONS : ""
```
## Sequence diagrams

#### 1. Классификация транзакции (с гибридным подходом ML + LLM)
```mermaid
sequenceDiagram
    participant Generator
    participant Kafka
    participant Core
    participant Classifier as Classifier Service
    participant DB
    participant Reasoning as Transaction Classifier Agent
    participant LLM as LLM Provider

    Generator->>Kafka: raw-transaction
    activate Kafka
    Kafka-->>Core: consume raw-transaction
    deactivate Kafka

    activate Core
    Core->>Classifier: REST /classify (transaction)
    activate Classifier
    Classifier-->>Core: category, confidence (0.65)
    deactivate Classifier

    alt confidence < threshold (0.9)
        Core->>Kafka: llm-classifier-request (txId, correlationId)
        activate Kafka
        Kafka-->>Reasoning: consume request
        deactivate Kafka
        
        activate Reasoning
        Reasoning->>DB: get user context (transactions)
        activate DB
        DB-->>Reasoning: user history
        deactivate DB
        Reasoning->>LLM: call LLM with prompt
        activate LLM
        LLM-->>Reasoning: LLM response (category, confidence)
        deactivate LLM
        Reasoning->>Kafka: llm-classifier-response (category, correlationId)
        activate Kafka
        Kafka-->>Core: consume response
        deactivate Kafka
        deactivate Reasoning
        
        Core->>DB: save transaction with LLM category
    else confidence >= threshold
        Core->>DB: save transaction with ML category
    end
    deactivate Core
```


#### 2. Генерация рекомендации (по ручному запросу)
```mermaid
sequenceDiagram
    participant User
    participant Core
    participant Kafka
    participant Coach as Financial Coach Agent
    participant DB
    participant LLM as LLM Provider
    participant Notify as Notify Service
    participant TG as Telegram Bot

    User->>Core: POST /users/{id}/recommendations/refresh
    activate Core
    Core->>Kafka: coach-request (userId, requestId)
    deactivate Core
    Core-->>User: 202 Accepted (requestId)

    activate Kafka
    Kafka-->>Coach: consume coach-request
    deactivate Kafka

    activate Coach
    Coach->>DB: get transactions (last 30 days)
    activate DB
    DB-->>Coach: transaction list
    deactivate DB

    Coach->>Coach: call tools (spending_by_category, monthly_delta, etc.)
    Coach->>LLM: call LLM with tools result
    activate LLM
    LLM-->>Coach: recommendation text + structured data
    deactivate LLM

    Coach->>DB: save recommendation (advice_data JSONB)
    activate DB
    DB-->>Coach: saved
    deactivate DB

    Coach->>Kafka: coach-response (userId, recommendationId)
    deactivate Coach

    activate Kafka
    Kafka-->>Notify: consume coach-response
    deactivate Kafka

    activate Notify
    Notify->>DB: get recommendation by id
    activate DB
    DB-->>Notify: advice_data
    deactivate DB
    Notify->>TG: send message (Telegram API)
    deactivate Notify

    User->>Core: GET /users/{id}/recommendations
    activate Core
    Core->>DB: get latest recommendations
    activate DB
    DB-->>Core: recommendations list
    deactivate DB
    Core-->>User: 200 OK (recommendations)
    deactivate Core
```

## State Diagram
```mermaid
stateDiagram-v2

    [*] --> NEW

    NEW --> ML_CLASSIFYING : TransactionCreatedEvent

    ML_CLASSIFYING --> ML_CLASSIFIED : confidence >= threshold
    ML_CLASSIFYING --> LLM_CLASSIFYING : confidence < threshold
    ML_CLASSIFYING --> RETRYING : ML error

    LLM_CLASSIFYING --> CLASSIFIED : success
    LLM_CLASSIFYING --> RETRYING : LLM error

    RETRYING --> ML_CLASSIFYING : retry ML
    RETRYING --> LLM_CLASSIFYING : retry LLM
    RETRYING --> FAILED : max retries exceeded

    ML_CLASSIFIED --> CLASSIFIED : persist result

    CLASSIFIED --> [*]
    FAILED --> [*]

```

## Deployment Diagram

```mermaid
flowchart TB
    subgraph "Docker Host"
        subgraph "Infrastructure (docker-compose.infrastructure.yml)"
            Postgres[(Postgres)]
            Kafka[(Kafka)]
            Zookeeper[Zookeeper]
        end

        subgraph "FinSense Services (docker-compose.services.yml)"
            Core[Core Service]
            Classifier[Classifier Service]
            Reasoning[Transaction Classifier Agent]
            Coach[Financial Coach Agent]
            Notify[Notify Service]
        end
    end

    subgraph "External Systems"
        User(User)
        Generator[Transaction Generator]
        LLM[LLM Provider<br/>OpenAI / YandexGPT]
        TG[Telegram Bot API]
    end

    %% Infrastructure connections inside Docker Host
    Postgres -->|5432/tcp| Core
    Postgres -->|5432/tcp| Classifier
    Postgres -->|5432/tcp| Reasoning
    Postgres -->|5432/tcp| Coach
    Postgres -->|5432/tcp| Notify

    Kafka -->|9092/tcp| Core
    Kafka -->|9092/tcp| Reasoning
    Kafka -->|9092/tcp| Coach
    Kafka -->|9092/tcp| Notify

    Zookeeper -.->|manages| Kafka

    %% Connections between FinSense Services
    Core -->|HTTP /classify| Classifier
    Core -->|publishes/consumes| Kafka
    Core -->|JDBC| Postgres

    Classifier -->|JDBC| Postgres

    Reasoning -->|consumes/publishes| Kafka
    Reasoning -->|HTTPS| LLM
    Reasoning -->|JDBC| Postgres

    Coach -->|consumes/publishes| Kafka
    Coach -->|HTTPS| LLM
    Coach -->|JDBC| Postgres

    Notify -->|consumes coach-responses| Kafka
    Notify -->|HTTPS| TG
    Notify -->|JDBC| Postgres

    %% Connections with External Systems
    User -->|HTTPS 8080| Core
    Generator -->|publishes raw-transactions| Kafka
    LLM -->|HTTPS| Reasoning
    LLM -->|HTTPS| Coach
    TG -->|HTTPS| Notify
```
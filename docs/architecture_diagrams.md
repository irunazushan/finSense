## System Context Diagram


```mermaid
graph TB
    subgraph "Внешние системы"
        LLM[LLM Provider<br>OpenAI/DeepSeek]
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
    
    Coach -->|LLM calls| LLM
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
        string number UK
        string type
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
    Classifier-->>Core: category, confidence (0.95)
    deactivate Classifier

    alt confidence < threshold (0.9)
        Core->>Kafka: llm-classifier-request (txId, requestId)
        activate Kafka
        Kafka-->>Reasoning: consume request
        deactivate Kafka
        
        activate Reasoning
       
        Reasoning->>LLM: call LLM with prompt
        activate LLM
        LLM-->>Reasoning: LLM response (category, confidence)
        deactivate LLM
        Reasoning->>Kafka: llm-classifier-response (category, requestId)
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
    Coach->>DB: get transactions (last n days)
    activate DB
    DB-->>Coach: transaction list
    deactivate DB

    Coach->>Coach: call tools (spending_by_category, monthly_delta, etc.)
    Coach->>LLM: call LLM with a prompt enriched by the tools result
    activate LLM
    LLM-->>Coach: recommendation
    deactivate LLM

    Coach->>DB: save recommendation (advice_data JSONB)
    activate DB
    DB-->>Coach: saved
    deactivate DB

    Coach->>Kafka: coach-response (userId, requestId)
    deactivate Coach

    activate Kafka
    Kafka-->>Notify: consume coach-response
    deactivate Kafka

    activate Notify
    
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

    NEW --> ML_CLASSIFYING : raw-transaction consumed

    ML_CLASSIFYING --> CLASSIFIED : confidence >= threshold\n(save category=ML)
    ML_CLASSIFYING --> LLM_CLASSIFYING : confidence < threshold\n(publish llm-classifier-request)
    ML_CLASSIFYING --> RETRYING : ML error

    LLM_CLASSIFYING --> CLASSIFIED : llm-classifier-response received\n(save category=LLM)
    LLM_CLASSIFYING --> RETRYING : LLM processing error
    LLM_CLASSIFYING --> FAILED : timeout waiting LLM response

    RETRYING --> ML_CLASSIFYING : retry
    RETRYING --> LLM_CLASSIFYING : retry
    RETRYING --> FAILED : max retries exceeded

    CLASSIFIED --> [*]
    FAILED --> [*]
```

## Deployment Diagram

```mermaid
flowchart TB

subgraph EXT["External Systems"]
    User["User (Browser / Client)"]
    Generator["Transaction Generator"]
    LLM["LLM Provider<br/>OpenAI / DeepSeek"]
    TG["Telegram Bot API"]
end

subgraph HOST["Docker Host"]

    subgraph INF["Infrastructure (docker-compose.infrastructure.yml)"]
        Postgres[(Postgres :5432)]
        Kafka[(Kafka :9092)]
        ZK["Zookeeper"]
        ZK -. manages .-> Kafka
    end

    subgraph SVC["FinSense Services (docker-compose.services.yml)"]
        Core["Core Service (:8080)"]
        Classifier["Classifier Service (:8081)"]
        Reasoning["Transaction Classifier Agent"]
        Coach["Financial Coach Agent"]
        Notify["Notify Service"]
    end
end

User -->|"HTTPS :8080"| Core
Generator -->|"Kafka :9092<br/>raw-transactions"| Kafka

Core -->|"HTTP :8081 /api/classify"| Classifier

Core <-->|"Kafka :9092<br/>produce/consume"| Kafka
Reasoning <-->|"Kafka :9092<br/>consume/produce"| Kafka
Coach <-->|"Kafka :9092<br/>consume/produce"| Kafka
Notify -->|"Kafka :9092<br/>consume coach-responses"| Kafka

Core -->|"JDBC :5432<br/>core schema"| Postgres
Coach -->|"JDBC :5432<br/>recommendations schema"| Postgres

Reasoning -->|"HTTPS :443"| LLM
Coach -->|"HTTPS :443"| LLM
Notify -->|"HTTPS :443<br/>sendMessage"| TG
```
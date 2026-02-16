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

## DB scheme

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

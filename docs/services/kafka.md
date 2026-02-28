# Kafka Topics

## Сводная таблица топиков

| Топик | Продюсер | Консьюмер | Ключ | Retention |
|-------|----------|--------------|------|-----------|
| `raw-transactions` | Generator | Core | `transactionId` | 7 дней |
| `llm-classifier-requests` | Core | Transaction Classifier Agent | `transactionId` | 3 дня |
| `llm-classifier-responses` | Transaction Classifier Agent | Core | `transactionId` | 3 дня |
| `coach-requests` | Core | Coach Agent | `userId` | 7 дней |
| `coach-responses` | Coach Agent | Notify Service | `userId` | 3 дня |

---

### 1. `raw-transactions`

**Назначение:**  
Сырые транзакции, поступающие от генератора для дальнейшей обработки.

**Продюсер:** Generator Service  
**Консьюмеры:** Core Service  
**Ключ:** `transactionId` (UUID) – обеспечивает порядок обработки для одной транзакции.  

**Пример сообщения:**
```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "amount": 1250.75,
  "description": "Кофе Starbucks",
  "merchantName": "Starbucks",
  "mccCode": "5812",
  "timestamp": "2026-02-17T12:34:56Z"
}
```
---

### 2. `llm-classifier-requests`

**Назначение:**  
Запросы на LLM-классификацию для транзакций, с которыми ML-классификатор не справился. Core Service добавляет в сообщение полные данные транзакции и историю последних транзакций пользователя.

**Продюсер:** Core Service  
**Консьюмеры:** Transaction Classifier Agent
**Ключ:** `transactionId` (UUID) – для связывания запроса и ответа.  
**Партиции:** 2  
**Replication factor:** 1  
**Retention:** 3 дня  

**Пример сообщения:**
```json
{
  "requestId": "b8e2c8a4-2f6a-4baf-9f1c-6f7e6c77a1d2",
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "occurredAt": "2026-02-10T10:15:30Z",
  "transaction": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "amount": 1250.75,
    "description": "Кофе Starbucks",
    "merchantName": "Starbucks",
    "mccCode": "5812",
    "timestamp": "2026-02-17T12:34:56Z"
  },
  "confidence": 0.62,
  "predictedCategory": "TRANSPORT",
  "history": [
    {
      "id": "660e8400-e29b-41d4-a716-446655440001",
      "amount": 500.00,
      "description": "Метро",
      "merchantName": "Московский метрополитен",
      "mccCode": "4111",
      "timestamp": "2026-02-16T08:15:00Z"
    },
    {
      "id": "770e8400-e29b-41d4-a716-446655440002",
      "amount": 3200.00,
      "description": "Ужин в ресторане",
      "merchantName": "Пушкинъ",
      "mccCode": "5812",
      "timestamp": "2026-02-15T20:30:00Z"
    }
  ]
}
```

---

### 3. `llm-classifier-responses`

**Назначение:**  
Результаты LLM-классификации от Reasoning Agent.

**Продюсер:** Reasoning Agent  
**Консьюмеры:** Core Service  
**Ключ:** `transactionId` (UUID) – тот же, что был в запросе.  
**Партиции:** 2  
**Replication factor:** 1  
**Retention:** 3 дня  

**Пример сообщения:**
```json
{
  "requestId": "b8e2c8a4-2f6a-4baf-9f1c-6f7e6c77a1d2",
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "category": "food_and_drinks",
  "confidence": 0.95,
  "processedAt": "2026-02-10T10:15:33Z"
}
```

---

### 4. `coach-requests`

**Назначение:**  
Запросы на генерацию персонализированных финансовых советов для пользователя. Могут отправляться по расписанию (scheduler) или по ручному запросу.

**Продюсер:** Core Service (scheduler + REST handler)  
**Консьюмеры:** Coach Agent  
**Ключ:** `userId` (UUID) – гарантирует последовательную обработку советов для одного пользователя.  
**Партиции:** 2  
**Replication factor:** 1  
**Retention:** 7 дней  

**Пример сообщения:**
```json
{
  "requestId": "d2e3f4a5-6b7c-8d9e-0f1a-2b3c4d5e6f7a",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "trigger": "manual",
  "requestedAt": "2026-02-17T15:30:00Z",
  "parameters": {
    "periodDays": 30,
    "message": "На какой категорий я мог бы сэкономить больше всего?"
  }
}
```
---

### 5. `coach-responses`

**Назначение:**  
Готовые финансовые советы, сгенерированные Coach Agent.

**Продюсер:** Coach Agent  
**Консьюмеры:** Notify Service
**Ключ:** `userId` (UUID)  
**Партиции:** 2  
**Replication factor:** 1  
**Retention:** 3 дня  

**Пример сообщения:**
```json
{
  "requestId": "f1e2d3c4-b5a6-4c3d-9e8f-7a6b5c4d3e2f",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "summary": "Вы тратите на кофе 5000 руб в месяц",
  "advice": "Попробуйте готовить дома — сэкономите до 3000 руб",
  "completedAt": "2026-02-25T15:30:12Z",
  "status": "COMPLETED"
}
```

---

## Примечания по настройке Kafka

- **Bootstrap servers**: `kafka:9092` (внутри Docker-сети)
- **Количество партиций** выбрано с учётом возможного параллелизма, но может быть изменено при масштабировании.
- Все топики используют **cleanup.policy=delete** (удаление по истечении retention).
- Для разработки replication factor = 1, в продакшене рекомендуется 3.

# Kafka Setup

## Версии и образы
- Kafka: `confluentinc/cp-kafka:7.5.0`
- Zookeeper: `confluentinc/cp-zookeeper:7.5.0`

## Docker Compose

```yaml
# фрагмент docker-compose.infrastructure.yml
zookeeper:
  image: confluentinc/cp-zookeeper:7.5.0
  environment:
    ZOOKEEPER_CLIENT_PORT: 2181

kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: kafka
    depends_on:
      - zookeeper
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    ports:
      - "9092:9092"
    networks:
      - finsense-net
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 10s
      timeout: 5s
      retries: 10

  kafka-init:
    image: confluentinc/cp-kafka:7.5.0
    container_name: kafka-init
    depends_on:
      kafka:
        condition: service_healthy
    entrypoint: ["/bin/bash", "-c", "/scripts/init-kafka-topics.sh"]
    volumes:
      - ./scripts:/scripts
    networks:
      - finsense-net
    restart: "no"
```

## Создание топиков

В проекте используется гибридный подход:

1. **Автоматическое создание** включено (`auto.create.topics.enable=true`) для удобства разработки.
2. При первом запуске выполняется **скрипт инициализации**, который создаёт топики с правильными параметрами (партиции, retention):

```bash
kafka-topics.sh --create --bootstrap-server localhost:9092 \
  --topic raw-transactions \
  --partitions 2 \
  --replication-factor 1
```

## Проверка работы
```bash
# Зайти в контейнер
docker exec -it kafka bash

# Посмотреть список топиков
kafka-topics --list --bootstrap-server localhost:9092

# Прочитать сообщения из топика
kafka-console-consumer --bootstrap-server localhost:9092 --topic raw-transactions --from-beginning
```

## Подключение в Spring Boot (примерно)
```yaml
spring:
  kafka:
    bootstrap-servers: kafka:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
```
# Transaction Classifier Agent — архитектурная документация

## 1. Роль сервиса в системе

### Бизнес-назначение
Transaction Classifier Agent (далее – Reasoning Agent) обрабатывает транзакции, которые не удалось классифицировать с достаточной уверенностью с помощью быстрого ML‑классификатора (Classifier Service). Агент использует большую языковую модель (LLM) для анализа контекста транзакции и истории пользователя, возвращает уточнённую категорию и уровень уверенности.

### Business capabilities
- **Контекстная классификация** – определение категории транзакции на основе LLM с учётом истории операций пользователя.
- **Интеграция с LLM** – формирование промптов, вызов внешнего API (DeepSeek), обработка ответов.
- **Обработка ошибок и повторные попытки** – при сбоях или некорректных ответах LLM выполняются повторные вызовы (до 3 раз), при исчерпании попыток транзакция направляется на ручную классификацию.
- **Асинхронное взаимодействие** – обмен сообщениями через Kafka (потребление запросов, публикация результатов).
- **Аудит и отладка** – сохранение полных логов запросов/ответов LLM в файловую систему для последующего анализа.

### Bounded context и данные
Сервис получает детали транзакции и историю пользователя через Kafka и результат классификации публикует так же в Kafka для дальнейшей обработки Core Service. Логи LLM хранятся исключительно в файлах, БД для этого не используется.

### Место в архитектуре
Reasoning Agent является **асинхронным процессором** (event-driven worker):
- Потребляет сообщения из топика `llm-classifier-requests`.
- Для каждого запроса вызывает LLM, публикует ответ в `llm-classifier-responses`.
- Не имеет собственного REST API, взаимодействует только через Kafka и БД.

---

## 2. Технологический стек
| Технология                      | Обоснование                                                                     |
| ------------------------------- | ------------------------------------------------------------------------------- |
| Kotlin                          | Современный, лаконичный, корутины для асинхронности, единообразие с Core.       |
| Spring Boot 3.x                 | Удобная интеграция с Kafka и JDBC, богатая экосистема.                          |
| Gradle (Kotlin DSL)             | Гибкость, декларативность.                                                      |
| Spring Kafka                    | `@KafkaListener`, `KafkaTemplate`, поддержка корутин.                           |
| Spring AI (ChatClient)          | Унифицированный доступ к LLM, встроенная поддержка промптов и function calling. |
| Logback + файловые appender'ы   | Запись полных промптов и ответов LLM в отдельные файлы.                         |
| Jackson / kotlinx.serialization | Обработка JSON для запросов/ответов LLM.                                        |
| Spring Boot Actuator            | Health checks, метрики.                                                         |
| Docker                          | Изоляция, воспроизводимость.                                                    |

---

## 3. Архитектура сервиса

### Логическая структура (слои)

| Слой                     | Компоненты / пакеты                                      | Ответственность                                                                 |
|--------------------------|----------------------------------------------------------|---------------------------------------------------------------------------------|
| **Kafka Listener**       | `com.finsense.reasoning.kafka.ClassifierRequestConsumer` | Потребление сообщений из `llm-classifier-requests`.                             |
| **Kafka Producer**       | `com.finsense.reasoning.kafka.ClassifierResponseProducer`| Публикация результатов в `llm-classifier-responses`.                            |
| **Service**              | `com.finsense.reasoning.service.ClassificationService`   | Координация процесса: получение контекста, вызов LLM, обработка ответа.         |
| **LLM Client**           | `com.finsense.reasoning.llm.LLMService`                  | Формирование промпта, вызов DeepSeek API, парсинг ответа.                       |
| **Logging**              | `com.finsense.reasoning.logging.LLMLogger`               | Сохранение логов LLM в файлы.                                                  |
| **Config**               | `com.finsense.reasoning.config.*`                        | Конфигурация Kafka, пулов соединений, параметров LLM.                          |

### Основные компоненты

| Компонент                          | Назначение                                                                 |
|------------------------------------|----------------------------------------------------------------------------|
| `ClassifierRequestConsumer`        | Слушает топик `llm-classifier-requests`, передаёт запрос в `ClassificationService`. |
| `ClassifierResponseProducer`       | Отправляет результат в `llm-classifier-responses`.      
| `ClassificationService`            | Центральный сервис: получает контекст из БД, вызывает LLM, обрабатывает результат. |
| `LLMService`                       | Формирует промпт, вызывает DeepSeek API, парсит JSON-ответ.                |
| `LLMLogger`                        | Сохраняет в файл: промпт, ответ, метаданные (модель, токены, время).      |                   |

### Потоки данных внутри сервиса

1. **Получение запроса**  
   `ClassifierRequestConsumer` получает сообщение из Kafka:
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

2. **Формирование промпта и вызов LLM**  
   `LLMService` строит промпт, используя:
    - Данные текущей транзакции
    - Историю транзакций
    - Список допустимых категорий

4. **Обработка ответа LLM**  
   - Парсинг JSON, ожидаемые поля: `category`, `confidence`, `reasoning`.
   - При успехе – публикация результата.
   - При ошибке или некорректном JSON – повтор (до 3 раз). Если все попытки исчерпаны – транзакция помечается для ручной классификации (в ответе отправляется специальный флаг или категория `UNDEFINED` с низкой уверенностью).

5. **Логирование**  
   `LLMLogger` записывает в файл:
   - промпт (полностью),
   - ответ LLM,
   - модель, затраченные токены, latency,
   - timestamp, correlationId (transactionId).

6. **Публикация результата**  
   `ClassifierResponseProducer` отправляет в топик `llm-classifier-responses`:
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

## 4. Взаимодействие с другими сервисами

### Потребляемые события (Kafka)

| Топик                     | Тип сообщения                                    | Назначение                              |
|---------------------------|--------------------------------------------------|-----------------------------------------|
| `llm-classifier-requests` | `{ trnsactionId, requestId, occurredAt, transaction, confidence, predictedCategory, history }`                      | Запрос на LLM‑классификацию             |

### Публикуемые события (Kafka)

| Топик                     | Тип сообщения                                    | Назначение                              |
|---------------------------|--------------------------------------------------|-----------------------------------------|
| `llm-classifier-responses`| `{ transactionId, requestId, category, confidence }`        | Результат классификации                  |

### Внешние API

| Сервис              | Метод | Назначение                                |
|---------------------|-------|-------------------------------------------|
| DeepSeek API        | POST  | Вызов LLM для классификации               |

---

## 5. Доменные сущности

| Сущность               | Тип         | Контекст                  | Краткое описание                                                                 |
|------------------------|-------------|---------------------------|----------------------------------------------------------------------------------|
| `TransactionData`      | JPA entity  | `core` (чтение)           | Детали транзакции: id, userId, amount, description, merchantName, mccCode, transactionDate. |
| `LLMRequest`           | DTO         | Внутренний                | Данные для формирования промпта.                                                 |
| `LLMResponse`          | DTO         | Внутренний                | Распарсенный ответ LLM: category, confidence, reasoning.                         |
| `ClassificationResult` | DTO         | Kafka                     | Результат для публикации: transactionId, category, confidence.                   |
| `LLMLogRecord`         | DTO         | Логирование               | Запись в файл: timestamp, transactionId, prompt, response, model, tokens, latency. |

---

## 6. Конфигурация

### Основной `application.yml`

```yaml
server:
  port: ${SERVER_PORT:8082}

spring:
  application:
    name: transaction-classifier-agent

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    consumer:
      group-id: reasoning-agent-group
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
        spring.json.value.default.type: com.finsense.reasoning.dto.ClassifierRequest
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

app:
  llm:
    provider: deepseek
    api-url: ${LLM_API_URL:https://api.deepseek.com/v1/chat/completions}
    api-key: ${LLM_API_KEY:}
    model: ${LLM_MODEL:deepseek-chat}
    max-tokens: 500
    temperature: 0.1
    timeout-seconds: 10
    retry:
      max-attempts: 3
      backoff-delay-ms: 1000

  history:
    transaction-limit: 20

  logging:
    llm-logs-dir: /var/log/finsense/llm-classifier
```

## 7. Запуск через Docker

### `Dockerfile` (многоступенчатая сборка)

```dockerfile
# ---- Builder stage ----
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY transaction-classifier-agent/build.gradle.kts settings.gradle.kts ./
COPY transaction-classifier-agent/src ./src
RUN gradle :transaction-classifier-agent:bootJar --no-daemon

# ---- Runner stage ----
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=builder /app/transaction-classifier-agent/build/libs/*.jar app.jar
# Создаём директорию для логов LLM
RUN mkdir -p /var/log/finsense/llm-classifier
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Фрагмент `docker-compose.services.yml`

```yaml
services:
  reasoning:
    build:
      context: ./transaction-classifier-agent
      dockerfile: Dockerfile
    container_name: reasoning-agent
    ports:
      - "8082:8082"
    environment:
      SERVER_PORT: 8082
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      LLM_API_KEY: ${DEEPSEEK_API_KEY}
      LLM_MODEL: deepseek-chat
    volumes:
      - ./logs/llm-classifier:/var/log/finsense/llm-classifier
    networks:
      - finsense-net
    depends_on:
      kafka:
        condition: service_healthy
    restart: unless-stopped
```

### Порядок запуска

1. Запустить инфраструктуру (Postgres, Kafka, Zookeeper, kafka-init):
   ```bash
   docker-compose -f docker-compose.infrastructure.yml up -d
   ```
2. Убедиться, что init‑контейнер `kafka-init` завершился успешно.
3. Запустить Reasoning Agent:
   ```bash
   docker-compose -f docker-compose.services.yml up -d reasoning
   ```

### Зависимости
- **PostgreSQL** – должна быть доступна и здорова (healthcheck).
- **Kafka** – должна быть доступна и здорова.
- **Топики Kafka** – должны быть созданы (обеспечивается `kafka-init`).

---

## 8. Логирование LLM

Логи сохраняются в файловой системе по пути, указанному в `app.logging.llm-logs-dir`. Формат файлов:

- Имя: `{transactionId}_{timestamp}.json`
- Содержимое:
  ```json
  {
    "timestamp": "2026-02-25T10:15:30Z",
    "transactionId": "550e8400-e29b-41d4-a716-446655440000",
    "userId": "123e4567-e89b-12d3-a456-426614174000",
    "model": "deepseek-chat",
    "prompt": "полный текст промпта...",
    "response": {
      "choices": [{"message": {"content": "{\"category\":\"FOOD_AND_DRINKS\",\"confidence\":0.95,\"reasoning\":\"...\"}"}}],
      "usage": {"total_tokens": 450}
    },
    "tokens": 450,
    "latencyMs": 1250,
    "success": true
  }
  ```

#### Используется rolling file strategy:

- Максимальный размер одного файла: 10 MB
- После превышения создаётся новый файл
- Максимум файлов: 100

#### Retention policy
- Хранение логов не более 30 дней
- Старые файлы автоматически удаляются
- Общий объём хранения ограничен 1 GB

Конфигурация (пример Logback)
```xml
<rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
    <fileNamePattern>/var/log/finsense/llm-classifier/llm.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
    <maxHistory>30</maxHistory>
    <totalSizeCap>1GB</totalSizeCap>
</rollingPolicy>

```
# Core Service — архитектурная документация

## 1. Роль сервиса в системе

### Бизнес-назначение
Core Service реализует Application Layer системы FinSense и координирует взаимодействие с ML и LLM компонентами. Он предоставляет внешний API для клиентов (пользователей), координирует процессы классификации транзакций и генерации финансовых рекомендаций, управляет основными данными и обеспечивает согласованность между остальными сервисами.

### Business capabilities
- Приём и обработка транзакций (получение из Kafka, классификация через ML или LLM, сохранение истории).
- Предоставление аналитики расходов и истории транзакций пользователям.
- Инициация генерации персонализированных рекомендаций (по расписанию и по запросу).
- Хранение и выдача сгенерированных рекомендаций.

## Bounded Context и владение данными
  
Core Service является владельцем доменной модели **Core Context** и управляет данными:

- **User** — учётные данные пользователя
- **Account** — счета пользователя
- **Transaction** — операции (сырые и классифицированные)

Данные Core Context хранятся в PostgreSQL (schema `core`) и считаются источником истины для этих сущностей.

**Recommendation Context (MVP):**
- рекомендации и статус их генерации хранятся в PostgreSQL (schema `recommendations`);
- запись создаётся Core Service (status=`PENDING`), обновляется Financial Coach Agent (status=`COMPLETED/FAILED`);
- доступ к рекомендациям пользователю предоставляется через REST API Core Service.

### Место в архитектуре
Core Service выполняет роль **оркестратора**:
- Принимает пользовательские запросы (синхронно через REST).
- Потребляет сырые транзакции из Kafka (от Generator).
- Вызывает **Classifier Service** синхронно для быстрой ML‑классификации.
- При низкой уверенности ML - core публикует событие в Kafka  для **Transaction Classifier Agent**.
- Потребляет результаты LLM‑классификации и обновляет статус транзакции.
- По расписанию или по запросу публикует события для **Financial Coach Agent**.
- Сохраняет финальные результаты в БД и отдаёт их клиентам по REST.

---

## 2. Технологический стек

| Технология              | Обоснование             |
|-------------------------|---------------------------|
| Kotlin                    | Современный, лаконичный, полная совместимость с Java, корутины.            |
| Spring Boot 3.x           | Стандарт индустрии для микросервисов, удобная интеграция с Kafka и JPA.    |
| Gradle (Kotlin DSL)       | Гибкость, декларативность, отличная поддержка Kotlin.                      |
| Spring Web                | Создание контроллеров, обработка запросов/ответов.                         |
| Spring Kafka              | Удобные шаблоны для продюсеров и консьюмеров, интеграция с корутинами.    |
| Spring Data JPA / Hibernate | ORM, репозитории, работа с сущностями.                                     |
| Liquibase                 | Версионирование схемы, воспроизводимость, поддержка сложных изменений.    |
| springdoc-openapi         | Автоматическая генерация OpenAPI-спецификации и Swagger UI.                |
| Spring Boot Actuator      | Health checks, метрики, аудит.                                              |
| Docker                    | Изоляция, воспроизводимость окружения.                                      |

---

## 3. Архитектура сервиса

### Логическая структура (слои)

| Слой                     | Компоненты / пакеты                                      | Ответственность                                                                 |
|--------------------------|----------------------------------------------------------|---------------------------------------------------------------------------------|
| **Controller**           | `com.finsense.core.api.controller`                       | Приём REST-запросов, валидация, возврат DTO.                                   |
| **Service**              | `com.finsense.core.service`                              | Бизнес-логика: обработка транзакций, координация вызовов, работа с репозиториями. |
| **Domain**               | `com.finsense.core.model`                               | JPA-сущности, аннотации, маппинг таблиц.                                       |
| **Repository**           | `com.finsense.core.repository`                           | Интерфейсы Spring Data JPA для доступа к БД.                                   |
| **Infrastructure**       | `com.finsense.core.infrastructure.kafka`                 | Kafka-продюсеры и консьюмеры.                                                  |
|                          | `com.finsense.core.infrastructure.client`                | HTTP-клиенты (ClassifierClient).                                               |
| **Config**               | `com.finsense.core.config`                               | Конфигурационные классы (Kafka, Liquibase, общие настройки).                   |

### Основные компоненты (справочно)

- `UserController`, `TransactionController`, `RecommendationController` – REST-эндпоинты.
- `UserService`, `TransactionService`, `RecommendationService` – реализация бизнес-логики.
- `RawTransactionConsumer` – потребляет сообщения из `raw-transactions`.
- `LlmClassifierResponseConsumer` – потребляет результаты LLM-классификации.
- `LlmClassifierRequestProducer` – публикует запросы в `llm-classifier-requests`.
- `CoachRequestProducer` – публикует запросы в `coach-requests`.
- `ClassifierClient` – Feign-клиент для синхронного вызова Classifier Service.

### Потоки данных (кратко)

1. **Входящая транзакция:** `RawTransactionConsumer` → `TransactionService` → сохранение в БД → вызов `ClassifierClient` → при низкой уверенности → `LlmClassifierRequestProducer`.
2. **LLM-ответ:** `LlmClassifierResponseConsumer` → `TransactionService` → обновление транзакции в БД.
3. **Генерация рекомендаций:** Scheduler или ручной вызов → `RecommendationService` → `CoachRequestProducer`.

#### Async Financial Coach Requests

Core Service инициирует генерацию финансовой рекомендации асинхронно через Kafka и отслеживает статус выполнения через таблицу `recommendations.recommendations`.

### Поток выполнения

1. **Создание запроса**
   - Core генерирует `requestId` (UUID).
   - Создаёт запись в `recommendations.recommendations` со статусом `PENDING`.
   - Публикует сообщение в Kafka topic `coach-requests` с этим `requestId`.

2. **Обновление статуса**
   - Financial Coach Agent читает `coach-requests`, выполняет анализ и обновляет запись в `recommendations.recommendations`:
     - `status = COMPLETED` + заполняет `advice_data`, `completed_at`
     - либо `status = FAILED` + заполняет `error`, `completed_at`

3. **Получение результата пользователем**
   - Core отдаёт пользователю статус и результат по `requestId` через REST API (например `GET /api/recommendations/{requestId}`).

## Transaction Statuses (Core Service)

Транзакция в Core Service проходит через конечный набор состояний (enum `TransactionStatus`).

### Список статусов

- **NEW**  
  Транзакция сохранена в БД, но ещё не обработана классификатором.

- **ML_CLASSIFYING**  
  Выполняется синхронная ML-классификация.

- **LLM_CLASSIFYING**  
  ML confidence ниже порога.  
  Отправлен `llm-classifier-request` в Kafka, ожидается `llm-classifier-response`.

- **CLASSIFIED**  
  Транзакция успешно классифицирована:
  - либо ML (confidence ≥ threshold),
  - либо LLM (fallback).

  Поля `category`, `classifier_source`, `classifier_confidence`
  заполнены и считаются финальными.

- **RETRYING**  
  Произошла ошибка при ML или при обработке LLM-ответа.  
  Выполняется повторная попытка (retry).

- **FAILED**  
  Транзакция не может быть обработана:
  - превышен лимит retry,
  - произошёл timeout ожидания LLM,
  - критическая ошибка обработки.

---

## Логика переходов (упрощённо)

- NEW → ML_CLASSIFYING
- ML_CLASSIFYING → CLASSIFIED (если confidence ≥ threshold)
- ML_CLASSIFYING → LLM_CLASSIFYING (если confidence < threshold)
- LLM_CLASSIFYING → CLASSIFIED (при получении LLM ответа)
- Любое состояние → RETRYING (при временной ошибке)
- RETRYING → ML_CLASSIFYING (повтор)
- RETRYING → FAILED (если превышен maxRetries)
- LLM_CLASSIFYING → FAILED (если timeout ожидания ответа)

---

## 4. REST API (контракт)

### Таблица эндпоинтов

| Метод | URL                                      | Описание                                                   |
| ----- | ---------------------------------------- | ---------------------------------------------------------- |
| GET   | `/api/v1/users/{userId}/recommendations` | Лента последних рекомендаций (COMPLETED)                   |
| POST  | `/api/v1/users/{userId}/recommendations` | Создать запрос на новую рекомендацию → вернуть `requestId` |
| GET   | `/api/v1/recommendations/{requestId}`    | Получить статус/результат конкретного запроса              |
| GET   | `/api/v1/users/{userId}/transactions`    | Список транзакций с фильтрами                              |
| GET   | `/actuator/health`      | Healthcheck                                                |


---

## 5. Взаимодействие с другими сервисами

### Публикуемые события (Kafka)

| Топик                     | Тип сообщения                                    | Назначение                              |
|---------------------------|--------------------------------------------------|-----------------------------------------|
| `llm-classifier-requests` | `{ requestId, transactionId, occuredAt, transaction, confidence, predictedCategory, history }`                      | Запрос на LLM‑классификацию  с контекстом             |
| `coach-requests`          | `{ requestId, userId, trigger, requestedAt, parameters? }` | Запрос на генерацию совета              |

### Потребляемые события (Kafka)

| Топик                     | Тип сообщения                                    | Назначение                              |
|---------------------------|--------------------------------------------------|-----------------------------------------|
| `raw-transactions`        | `{ transactionId, userId, amount, description, merchantName, mccCode, timestamp }` | Получение сырых транзакций от Generator |
| `llm-classifier-responses`| `{ requestId, transactionId, category, confidence, processedAt }`        | Результат LLM‑классификации              |

### REST‑взаимодействие

| Сервис              | Метод | URL          | Описание                                |
|---------------------|-------|--------------|-----------------------------------------|
| Classifier Service  | POST  | `/classify`  | Синхронная ML‑классификация транзакции |

### Краткое описание внешних сервисов

- **Generator Service** – генерирует синтетические транзакции, публикует в `raw-transactions`. Используется для тестирования.
- **Classifier Service** – ML‑классификатор на Java + Smile (или временно правила). Возвращает категорию и уверенность.
- **Transaction Classifier Agent** – агент на LLM для сложных случаев. Потребляет `llm-classifier-requests`, публикует ответы в `llm-classifier-responses`.
- **Financial Coach Agent** – агент на LLM для советов. Потребляет `coach-requests`, сохраняет советы в БД, опционально публикует `coach-responses` для Notify Service.
- **Notify Service** (опционально) – слушает `coach-responses`, отправляет уведомления в Telegram.

---

## 6. Доменные сущности


| Сущность                | Тип        | Контекст                               | Краткое описание                                                                                                                                                                                                                                     |
| ----------------------- | ---------- | -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `User`                  | JPA entity | `core` (владение)                      | Пользователь системы: `id`, `email/username`, `createdAt`, настройки/статусы.                                                                                                                                                                        |
| `Account`               | JPA entity | `core` (владение)                      | Счёт пользователя: `id`, `userId`, `name/type`, `createdAt`.                                                                                                                                                                                         |
| `Transaction`           | JPA entity | `core` (владение)                      | Финансовая операция: `id`, `userId`, `accountId`, `amount`, `description`, `merchantName`, `mccCode`, `transactionDate`, `category`, `status`, `classifierSource`, `classifierConfidence`.                                                           |
| `TransactionStatus`     | Enum       | `core`                                 | Статусы транзакции (state machine): `NEW`, `ML_CLASSIFYING`, `WAITING_LLM_RESULT`, `CLASSIFIED`, `RETRYING`, `FAILED`.                                                                                                                               |
| `TransactionCategory`   | Enum       | Shared Contract                        | Единый набор категорий транзакций, используемый в Core и внешних контрактах (Classifier/Agents).                                                                                                                                                     |
| `Recommendation`        | JPA entity | `recommendations` (чтение/оркестрация) | Асинхронный запрос/результат рекомендации по `requestId`: `id`, `userId`, `createdAt`, `status`, `completedAt`, `adviceData` (JSONB), `requestParams` (JSONB), `error`. (Запись создаёт Core как `PENDING`, обновляет Coach как `COMPLETED/FAILED`.) |
| `RecommendationStatus`  | Enum       | `recommendations`                      | Статусы генерации рекомендации: `PENDING`, `COMPLETED`, `FAILED`.                                                                                                                                                                                    |
| `RawTransactionEvent`   | DTO        | Kafka                                  | Сообщение из `raw-transactions`, которое Core потребляет и превращает в `Transaction`.                                                                                                                                                               |
| `CoachRequest`          | DTO        | Kafka                                  | Сообщение в `coach-requests`, которое Core публикует для запуска генерации рекомендации (`requestId`, `userId`, параметры).                                                                                                                          |
| `LlmClassifierRequest`  | DTO        | Kafka                                  | Сообщение в `llm-classifier-requests` при fallback на LLM-классификацию (`requestId`, `transactionId`, контекст/история).                                                                                                                            |
| `LlmClassifierResponse` | DTO        | Kafka                                  | Ответ из `llm-classifier-responses`, по которому Core завершает классификацию транзакции (`requestId`, `transactionId`, `category`, `confidence`, метаданные).                                                                                       |

---

## 7. Конфигурация

### Основной `application.yml`

```yaml
server:
  port: ${SERVER_PORT:8080}

spring:
  application:
    name: core-service

  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/finsense}
    username: ${DB_USER:finsense}
    password: ${DB_PASSWORD:finsense}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        default_schema: core
    show-sql: false

  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.yaml
    default-schema: core
    liquibase-schema: public

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
        spring.json.value.default.type: com.finsense.core.dto.kafka.KafkaMessage

app:
  classifier:
    url: ${CLASSIFIER_URL:http://classifier:8081}
    confidence-threshold: ${CLASSIFIER_CONFIDENCE_THRESHOLD:0.9}
  scheduler:
    coach-cron: ${COACH_SCHEDULER_CRON:0 0 2 * * ?}
  reasoning:
    history-size: ${REASONING_HISTORY_SIZE:20}
```

## 8. Контейнеризация и запуск в Docker
  
Путь к Dockerfile: *finSense/core-service/Dockerfile*

#### Фрагмент docker-compose.services.yml

```yaml
version: '3.8'

services:
  core:
    build:
      context: ./core-service
      dockerfile: Dockerfile
    container_name: core-service
    ports:
      - "8080:8080"
    environment:
      SERVER_PORT: 8080
      DB_URL: jdbc:postgresql://postgres:5432/finsense
      DB_USER: finsense
      DB_PASSWORD: finsense
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      CLASSIFIER_URL: http://classifier:8081
      CLASSIFIER_CONFIDENCE_THRESHOLD: 0.9
      COACH_SCHEDULER_CRON: "0 0 2 * * ?"
    networks:
      - finsense-net
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy
    restart: unless-stopped
```
### Порядок запуска
Запустить инфраструктуру (Postgres, Kafka, Zookeeper) и init‑контейнер Kafka:
```bash
docker-compose -f docker-compose.infrastructure.yml up -d
```
Запустить Core Service (и остальные сервисы):
```bash
docker-compose -f docker-compose.services.yml up -d core
```
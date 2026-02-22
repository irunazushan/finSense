# Core Service — архитектурная документация

## 1. Роль сервиса в системе

### Бизнес-назначение
Core Service реализует Application Layer системы FinSense и координирует взаимодействие с ML- и LLM-компонентами. Он предоставляет внешний API для клиентов (пользователей), координирует процессы классификации транзакций и генерации финансовых рекомендаций, управляет основными данными и обеспечивает согласованность между остальными сервисами.

### Business capabilities
- Приём и обработка транзакций (получение из Kafka, классификация через ML или LLM, сохранение истории).
- Предоставление аналитики расходов и истории транзакций пользователям.
- Инициация генерации персонализированных рекомендаций (по расписанию и по запросу).
- Хранение и выдача сгенерированных рекомендаций.

### Bounded context и данные
Core Service отвечает за следующие агрегаты:
- **User** – учётные данные пользователя.
- **Account** – финансовые счета пользователя.
- **Transaction** – сырые и классифицированные операции.
- **Recommendation** – готовые советы для пользователя.

Все эти данные хранятся в PostgreSQL и являются источником для остальных сервисов.

### Место в архитектуре
Core Service выполняет роль **оркестратора**:
- Принимает пользовательские запросы (синхронно через REST).
- Потребляет сырые транзакции из Kafka (от Generator).
- Вызывает **Classifier Service** синхронно для быстрой ML‑классификации.
- При низкой уверенности ML публикует событие в Kafka для **Transaction Classifier Agent**.
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

---

## 4. REST API (контракт)

### Таблица эндпоинтов

| Метод | URL                                            | Описание                                        | Основные параметры                         |
|-------|------------------------------------------------|-------------------------------------------------|--------------------------------------------|
| GET   | `/api/v1/users/{userId}/recommendations`       | Получить последние рекомендации пользователя    | `userId` (path), `limit` (query)           |
| POST  | `/api/v1/users/{userId}/recommendations/new` | Запросить генерацию новой рекомендации          | `userId` (path)                            |
| GET   | `/api/v1/users/{userId}/transactions`          | Получить список транзакций с фильтрацией        | `userId` (path), `startDate`, `endDate`, `category`, `limit`, `offset` |
| GET   | `/api/v1/users/{userId}/analytics`             | Получить аналитику расходов по категориям       | `userId` (path), `period` или `startDate`/`endDate` |
| GET   | `/health`                                      | Проверка состояния сервиса                      | –                                          |

*Полная спецификация запросов/ответов доступна в Swagger UI по `/swagger-ui.html` после запуска сервиса.*

---

## 5. Взаимодействие с другими сервисами

### Публикуемые события (Kafka)

| Топик                     | Тип сообщения                                    | Назначение                              |
|---------------------------|--------------------------------------------------|-----------------------------------------|
| `llm-classifier-requests` | `{ transactionId, userId }`                      | Запрос на LLM‑классификацию             |
| `coach-requests`          | `{ requestId, userId, trigger, requestedAt, parameters? }` | Запрос на генерацию совета              |

### Потребляемые события (Kafka)

| Топик                     | Тип сообщения                                    | Назначение                              |
|---------------------------|--------------------------------------------------|-----------------------------------------|
| `raw-transactions`        | `{ transactionId, userId, amount, description, merchantName, mccCode, timestamp }` | Получение сырых транзакций от Generator |
| `llm-classifier-responses`| `{ transactionId, category, confidence }`        | Результат LLM‑классификации              |

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

| Сущность         | Таблица (схема)          | Контекст (агрегат) | Краткое описание                                                                 |
|------------------|--------------------------|---------------------|----------------------------------------------------------------------------------|
| `User`           | `core.users`             | User                | Пользователь системы. Поля: `id`, `email`, `created_at`.                         |
| `Account`        | `core.accounts`          | User                | Банковский счёт пользователя. Поля: `id`, `user_id`, `number`, `type`, `currency`, `created_at`. |
| `Transaction`    | `core.transactions`      | Transaction         | Транзакция. Поля: `id`, `account_id`, `user_id`, `amount`, `description`, `merchant_name`, `mcc_code`, `transaction_date`, `status`, `category`, `classifier_source`, `classifier_confidence`, `classified_at`. |
| `Recommendation` | `recommendations.recommendations` | Recommendation | Рекомендация. Поля: `id`, `user_id`, `created_at`, `advice_data` (JSONB).        |

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
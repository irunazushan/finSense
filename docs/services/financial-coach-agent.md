# Financial Coach Agent — архитектурная документация

## 1. Роль сервиса в системе

### Бизнес-назначение
Financial Coach Agent (далее – Coach Agent) генерирует персонализированные финансовые советы для пользователей на основе анализа их транзакций. Сервис работает асинхронно, получая запросы через Kafka, анализирует данные за указанный период, использует LLM для формулирования рекомендаций и сохраняет результаты в БД.

### Business capabilities
- **Анализ транзакций** – агрегация данных по категориям, сравнение с прошлым периодом, выявление топ-мерчантов и аномалий.
- **Генерация советов** – формирование промптов на основе результатов анализа, вызов LLM (DeepSeek), получение текстовых рекомендаций.
- **Обработка параметризованных запросов** – возможность задавать период анализа и конкретный вопрос пользователя.
- **Асинхронное взаимодействие** – потребление запросов из Kafka (`coach-requests`), публикация результатов в Kafka (`coach-responses`).
- **Хранение рекомендаций** – сохранение сгенерированных советов в JSONB-поле таблицы `recommendations` для последующего отображения через Core API.

### Bounded context и данные
Сервис **читает** транзакции из общей БД (схема `core`), **записывает** рекомендации в схему `recommendations`. Не изменяет транзакции и не владеет ими.

### Место в архитектуре
Coach Agent является **асинхронным аналитическим процессором**:
- Потребляет сообщения из топика `coach-requests` (от Core Service).
- Для каждого запроса обращается к БД, выполняет агрегатные запросы, вызывает LLM.
- Сохраняет результат в БД и опционально публикует событие в `coach-responses` для Notify Service.
- Не имеет собственного REST API, полностью event-driven.

---

## 2. Технологический стек

| Компонент               | Технология                     | Обоснование                                                                 |
|-------------------------|--------------------------------|-----------------------------------------------------------------------------|
| Язык                    | Kotlin                         | Современный, лаконичный, корутины, единообразие с Core.                     |
| Фреймворк               | Spring Boot 3.x                | Интеграция с Kafka, JDBC, удобное конфигурирование.                         |
| Сборка                  | Gradle (Kotlin DSL)            | Гибкость, декларативность.                                                  |
| Работа с Kafka          | Spring Kafka                   | `@KafkaListener`, `KafkaTemplate`.                                          |
| Доступ к БД             | Spring Data JPA / Hibernate    | Чтение транзакций, запись рекомендаций.                                     |
| AI-клиент | Spring AI (ChatClient) | Унифицированный доступ к LLM, встроенная поддержка промптов и function calling || Логирование             | Logback + файловые appender'ы  | Запись промптов и ответов LLM в файлы.                                      |
| Сериализация            | Jackson / kotlinx.serialization | Обработка JSON.                                                             |
| Мониторинг              | Spring Boot Actuator           | Health checks, метрики.                                                     |
| Контейнеризация         | Docker                         | Изоляция, воспроизводимость.                                                |

---

## 3. Архитектура сервиса

### Логическая структура (слои)

| Слой                     | Компоненты / пакеты                                      | Ответственность                                                                 |
|--------------------------|----------------------------------------------------------|---------------------------------------------------------------------------------|
| **Kafka Listener**       | `com.finsense.coach.kafka.CoachRequestConsumer`          | Потребление сообщений из `coach-requests`.                                      |
| **Service**              | `com.finsense.coach.service.CoachService`                | Оркестрация: получение данных, вызов инструментов, формирование промпта.        |
| **Analytics**            | `com.finsense.coach.analytics.TransactionAnalyzer`       | Реализация инструментов (SQL-запросы): траты по категориям, сравнение с прошлым, топ-мерчанты, всплески. |
| **LLM Client**           | `com.finsense.coach.llm.LLMService`                      | Формирование промпта, вызов DeepSeek API, парсинг ответа.                       |
| **Repository**           | `com.finsense.coach.repository.*`                        | Доступ к `core.transactions` и `recommendations.recommendations`.               |
| **Logging**              | `com.finsense.coach.logging.LLMLogger`                   | Сохранение логов LLM в файлы.                                                   |
| **Kafka Producer**       | `com.finsense.coach.kafka.CoachResponseProducer`         | Публикация результатов в `coach-responses`.                                     |
| **Config**               | `com.finsense.coach.config.*`                            | Конфигурация Kafka, пулов соединений, параметров LLM.                           |

### Основные компоненты

| Компонент                          | Назначение                                                                 |
|------------------------------------|----------------------------------------------------------------------------|
| `CoachRequestConsumer`             | Слушает топик `coach-requests`, передаёт запрос в `CoachService`.          |
| `CoachService`                     | Центральный сервис: извлекает параметры, вызывает `TransactionAnalyzer`, формирует промпт, вызывает LLM, сохраняет результат. |
| `TransactionAnalyzer`              | Содержит методы-инструменты для получения агрегированных данных из БД.     |
| `TransactionRepository`            | Чтение транзакций пользователя за период.                                  |
| `RecommendationRepository`         | Сохранение совета в таблицу `recommendations`.                             |
| `LLMService`                       | Формирует промпт, вызывает DeepSeek API, парсит ответ.                     |
| `LLMLogger`                        | Сохраняет в файл: промпт, ответ, метаданные.                               |
| `CoachResponseProducer`            | Отправляет результат в `coach-responses` (если требуется уведомление).     |

### Потоки данных внутри сервиса

1. **Получение запроса**  
   `CoachRequestConsumer` получает сообщение из Kafka:
   ```json
   {
     "userId": "123e4567-e89b-12d3-a456-426614174000",
     "trigger": "manual",
     "requestedAt": "2026-02-25T15:30:00Z",
     "parameters": {
       "periodDays": 30,
       "message": "На какой категории я мог бы сэкономить больше всего?"
     }
   }
   ```

2. **Анализ данных**  
   `CoachService` вызывает методы `TransactionAnalyzer`:
   - `getSpendingByCategory(userId, periodDays)` – суммы по категориям.
   - `getMonthlyDelta(userId)` – сравнение с предыдущим периодом.
   - `getTopMerchants(userId, periodDays, limit = 5)` – топ-мерчанты.
   - `detectSpikes(userId, periodDays)` – категории с аномальным ростом.

3. **Формирование промпта**  
   `LLMService` строит промпт, включая результаты анализа и, если есть, `message` от пользователя.

4. **Вызов LLM**  
   Асинхронный запрос к DeepSeek API, получение текстового совета.

5. **Сохранение результата**  
   - Совет сохраняется в `recommendations` (JSONB-поле `advice_data`).
   - В JSONB также могут быть сохранены результаты вызовов инструментов для отладки.

6. **Публикация ответа (опционально)**  
   `CoachResponseProducer` отправляет событие в `coach-responses` для Notify Service:
   ```json
   {
     "recommendationId": "f1e2d3c4-b5a6-4c3d-9e8f-7a6b5c4d3e2f",
     "userId": "123e4567-e89b-12d3-a456-426614174000",
     "summary": "Больше всего вы тратите на покупки в интернет-магазинах — 40% бюджета...",
     "advice": "Попробуйте начать ходить в физические магазины, так как ...",
     "category": "shopping"
   }
   ```

7. **Логирование**  
   `LLMLogger` записывает промпт, ответ, метаданные в файл.

---

## 4. Взаимодействие с другими сервисами

### Потребляемые события (Kafka)

| Топик                     | Тип сообщения                                    | Назначение                              |
|---------------------------|--------------------------------------------------|-----------------------------------------|
| `coach-requests`          | `{ userId, trigger, requestedAt, parameters }`   | Запрос на генерацию совета               |

### Публикуемые события (Kafka)

| Топик                     | Тип сообщения                                    | Назначение                              |
|---------------------------|--------------------------------------------------|-----------------------------------------|
| `coach-responses`         | `{ recommendationId, userId, summary, advice, category }`| Уведомление о готовом совете (опционально) |

### Запросы к БД (PostgreSQL)

| Таблица (схема)                | Тип доступа | Описание                                |
|--------------------------------|-------------|-----------------------------------------|
| `core.transactions`            | чтение      | Получение транзакций пользователя за период. |
| `recommendations.recommendations` | запись   | Сохранение сгенерированного совета.      |

### Внешние API

| Сервис              | Метод | Назначение                                |
|---------------------|-------|-------------------------------------------|
| DeepSeek API        | POST  | Вызов LLM для генерации совета             |

---

## 5. Доменные сущности

| Сущность               | Тип         | Контекст                  | Краткое описание                                                                 |
|------------------------|-------------|---------------------------|----------------------------------------------------------------------------------|
| `CoachRequest`         | DTO         | Kafka                     | Входящее сообщение: `userId`, `trigger`, `requestedAt`, `parameters`.            |
| `TransactionData`      | JPA entity  | `core` (чтение)           | Транзакция: поля `amount`, `category`, `merchantName`, `timestamp` и др.        |
| `CategorySpending`     | Value object| Внутренний                | Сумма трат по категории.                                                         |
| `MerchantStat`         | Value object| Внутренний                | Статистика по мерчанту: имя, сумма, количество.                                  |
| `SpikeInfo`            | Value object| Внутренний                | Информация об аномалии: категория, среднее, текущее значение.                    |
| `LLMResponse`          | DTO         | Внутренний                | Ответ LLM (текст совета).                                                        |
| `Recommendation`       | JPA entity  | `recommendations` (запись)| Сохранённый совет: `userId`, `createdAt`, `advice_data` JSONB.                   |
| `CoachResponse`        | DTO         | Kafka                     | Исходящее сообщение: `recommendationId`, `userId`, `summary`, `category`.        |
| `LLMLogRecord`         | DTO         | Логирование               | Запись в файл: timestamp, userId, prompt, response, model, tokens, latency.     |

---

## 6. Конфигурация

### Основной `application.yml`

```yaml
server:
  port: ${SERVER_PORT:8083}

spring:
  application:
    name: financial-coach-agent

  datasource:
    url: ${DB_URL:jdbc:postgresql://postgres:5432/finsense}
    username: ${DB_USER:finsense}
    password: ${DB_PASSWORD:finsense}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2

  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
    show-sql: false

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    consumer:
      group-id: coach-agent-group
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
        spring.json.value.default.type: com.finsense.coach.dto.CoachRequest
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

app:
  llm:
    provider: deepseek
    api-url: ${LLM_API_URL:https://api.deepseek.com/v1/chat/completions}
    api-key: ${LLM_API_KEY:}
    model: ${LLM_MODEL:deepseek-chat}
    max-tokens: 300
    temperature: 0.3
    timeout-seconds: 15
  analytics:
    top-merchants-limit: 5
  logging:
    llm-logs-dir: /var/log/finsense/coach-llm
```
---

## 7. Запуск через Docker

### `Dockerfile` (многоступенчатая сборка)

```dockerfile
# ---- Builder stage ----
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY financial-coach-agent/build.gradle.kts settings.gradle.kts ./
COPY financial-coach-agent/src ./src
RUN gradle :financial-coach-agent:bootJar --no-daemon

# ---- Runner stage ----
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=builder /app/financial-coach-agent/build/libs/*.jar app.jar
RUN mkdir -p /var/log/finsense/coach-llm
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Фрагмент `docker-compose.services.yml`

```yaml
version: '3.8'

services:
  coach:
    build:
      context: ./financial-coach-agent
      dockerfile: Dockerfile
    container_name: coach-agent
    ports:
      - "8083:8083"
    environment:
      SERVER_PORT: 8083
      DB_URL: jdbc:postgresql://postgres:5432/finsense
      DB_USER: finsense
      DB_PASSWORD: finsense
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      LLM_API_KEY: ${DEEPSEEK_API_KEY}
      LLM_MODEL: deepseek-chat
      ANALYTICS_TOP_MERCHANTS_LIMIT: 5
    volumes:
      - ./logs/coach-llm:/var/log/finsense/coach-llm
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

1. Запустить инфраструктуру (Postgres, Kafka, Zookeeper, kafka-init):
   ```bash
   docker-compose -f docker-compose.infrastructure.yml up -d
   ```
2. Убедиться, что init‑контейнер `kafka-init` завершился успешно.
3. Запустить Coach Agent:
   ```bash
   docker-compose -f docker-compose.services.yml up -d coach
   ```

### Зависимости
- **PostgreSQL** – должна быть доступна и здорова (healthcheck).
- **Kafka** – должна быть доступна и здорова.
- **Топики Kafka** – должны быть созданы (обеспечивается `kafka-init`).

---

## 8. Обработка ошибок

- При ошибках вызова LLM (таймаут, сетевые проблемы) выполняется до 3 повторных попыток с экспоненциальной задержкой.
- Если все попытки исчерпаны, совет не генерируется, в лог записывается ошибка. Core при следующем запросе может инициировать повторную попытку (scheduler).
- В случае отсутствия транзакций у пользователя возвращается совет "У вас пока нет данных для анализа".

---


## 9. Логирование

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
      "message": "....",
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
    <fileNamePattern>/var/log/finsense/coach-llm/llm.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
    <maxHistory>30</maxHistory>
    <totalSizeCap>1GB</totalSizeCap>
</rollingPolicy>

```
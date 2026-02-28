# Notify Service — архитектурная документация

## 1. Роль сервиса в системе

### Бизнес-назначение

Notify Service реализует инфраструктурный слой доставки уведомлений в системе FinSense.

Сервис получает события о готовности финансовых рекомендаций из Kafka и отправляет уведомления пользователю через Telegram Bot API.

Notify Service:

* не содержит бизнес-логики анализа,
* не управляет доменными данными,
* не является владельцем доменной модели,
* выполняет исключительно функцию доставки сообщений.

---

## Business capabilities

* Потребление событий `coach-responses` из Kafka.
* Формирование текстового сообщения на основе полученного payload.
* Отправка уведомления пользователю через Telegram Bot API.
* Идемпотентная обработка повторных сообщений (MVP).

---

## Место в архитектуре

Notify Service выполняет роль **асинхронного downstream-потребителя событий**:

* Потребляет `coach-responses`, опубликованные Financial Coach Agent.
* Не взаимодействует напрямую с Core Service.
* Не обращается к PostgreSQL (в текущей MVP-реализации).
* Работает полностью через Kafka + внешний HTTP API Telegram.

Таким образом, Notify Service:

* не влияет на доменную логику,
* может масштабироваться независимо,
* может быть отключён без нарушения основной бизнес-логики.

---

## 2. Технологический стек

| Технология           | Обоснование                                 |
| -------------------- | ------------------------------------------- |
| Java 17     | Совместимость со стеком проекта.            |
| Spring Boot 3.x      | Быстрая разработка микросервиса.            |
| Spring Kafka         | Удобная реализация Kafka consumer.          |
| Spring WebClient     | Неблокирующий HTTP-клиент для Telegram API. |
| Spring Boot Actuator | Health checks и базовые метрики.            |
| Docker               | Изоляция и воспроизводимость окружения.     |

---

## 3. Архитектура сервиса

### Логическая структура (слои)

| Слой               | Компоненты / пакеты             | Ответственность                        |
| ------------------ | ------------------------------- | -------------------------------------- |
| **Kafka Listener** | `NotifyKafkaListener`           | Получение `CoachResponse` из Kafka.    |
| **Service**        | `NotificationService`           | Оркестрация отправки, идемпотентность. |
| **Formatter**      | `MessageFormatter`              | Формирование текста сообщения.         |
| **Client**         | `TelegramClient`                | Вызов Telegram Bot API.                |
| **Config**         | `KafkaConfig`, `TelegramConfig` | Конфигурация Kafka и Telegram.         |

---

### Основные компоненты (справочно)

* `NotifyKafkaListener` – слушает topic `coach-responses`.
* `NotificationService` – управляет логикой отправки и обработкой ошибок.
* `MessageFormatter` – формирует человекочитаемый текст.
* `TelegramClient` – отправляет HTTP-запрос в Telegram API.

---

## Потоки данных (кратко)

1. **Получение события:**
   `NotifyKafkaListener` потребляет сообщение из `coach-responses`.

2. **Формирование сообщения:**
   `NotificationService` передаёт данные в `MessageFormatter`.

3. **Отправка уведомления:**
   `TelegramClient` выполняет HTTP POST к Telegram Bot API.

---

## 4. Kafka-контракт

### Потребляемые события

| Топик             | Тип сообщения                                                 | Назначение                            |
| ----------------- | ------------------------------------------------------------- | ------------------------------------- |
| `coach-responses` | `{ requestId, userId, summary, advice, completedAt, status }` | Уведомление о готовности рекомендации |

### Пример payload

```json
{
  "requestId": "f1e2d3c4-b5a6-4c3d-9e8f-7a6b5c4d3e2f",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "status": "COMPLETED",
  "summary": "Вы тратите на кофе 5000 руб в месяц",
  "advice": "Попробуйте готовить дома — сэкономите до 3000 руб",
  "completedAt": "2026-02-25T15:30:12Z"
}
```

---

## 5. Взаимодействие с внешними системами

### Telegram Bot API

* Метод: `POST /bot{TOKEN}/sendMessage`
* Протокол: HTTPS (443)

Notify Service формирует payload:

```json
{
  "chat_id": "123456789",
  "text": "Ваш финансовый совет:\n\n...",
  "parse_mode": "HTML"
}
```

---

## 6. Идемпотентность (MVP)

Kafka использует модель доставки **at-least-once**, поэтому возможны повторные сообщения.

В MVP используется простая стратегия:

* Идентификатор идемпотентности — `requestId`.
* В памяти хранится набор уже обработанных `requestId`.
* При повторной доставке сообщение не отправляется повторно.

В дальнейшем возможно:

* использование Redis,
* или отдельной таблицы для хранения обработанных событий.

---

## 7. Конфигурация

### Основной `application.yml`

```yaml
server:
  port: 8084

spring:
  application:
    name: notify-service

  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
    consumer:
      group-id: notify-service
      auto-offset-reset: earliest
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"

telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
  chat-id: ${TELEGRAM_CHAT_ID}

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

---

## 8. Контейнеризация и запуск

### Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/notify-service.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Фрагмент docker-compose.services.yml

```yaml
notify:
  build:
    context: ./notify-service
    dockerfile: Dockerfile
  container_name: notify-service
  ports:
    - "8084:8084"
  environment:
    TELEGRAM_BOT_TOKEN: ${TELEGRAM_BOT_TOKEN}
    TELEGRAM_CHAT_ID: ${TELEGRAM_CHAT_ID}
  networks:
    - finsense-net
  restart: unless-stopped
```
# Notify Service — архитектурная документация

## 1. Роль сервиса в системе

### Бизнес-назначение

Notify Service отвечает за доставку пользователю уведомлений по результатам работы Financial Coach Agent.

Сервис:

- читает события из Kafka topic `coach-responses`
- формирует текст уведомления
- отправляет сообщение пользователю в Telegram через Telegram Bot API

Notify Service является **инфраструктурным сервисом доставки**, не содержит бизнес-логики финансового анализа.

---

## 2. Место в архитектуре

Поток данных:

Financial Coach Agent  
→ публикует `coach-responses` в Kafka  
→ Notify Service читает сообщение  
→ отправляет уведомление в Telegram  

Notify Service:

- не хранит транзакции
- не хранит рекомендации
- не изменяет доменные данные
- выполняет только доставку уведомлений

---

## 3. Kafka контракт

### Topic

`coach-responses`

### Key

`userId`

Это обеспечивает:

- последовательную обработку сообщений одного пользователя
- упрощённую корреляцию

### Value (CoachResponse)

```json
{
  "requestId": "f1e2d3c4-b5a6-4c3d-9e8f-7a6b5c4d3e2f",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "createdAt": "2026-02-17T15:31:00Z",
  "summary": "Вы тратите на кофе 5000 руб в месяц",
  "advice": "Попробуйте готовить дома — сэкономите до 3000 руб",
  "category": "spending",
  "createdAt": "2026-02-25T15:30:12Z"
}
```

---

## 4. Взаимодействие с Telegram

Notify Service использует Telegram Bot API:

POST  
https://api.telegram.org/bot{BOT_TOKEN}/sendMessage

Payload:

```
{
  "chat_id": "123456789",
  "text": "Ваш финансовый совет:\n\nБольше всего вы тратите на покупки...",
  "parse_mode": "HTML"
}
```

### Конфигурационные параметры

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID` (или маппинг userId → chatId в будущем)

---

## 5. Технологический стек

| Компонент        | Технология            | Обоснование |
|-----------------|----------------------|------------|
| Язык           | Kotlin / Java 17      | Единый стек проекта |
| Framework      | Spring Boot 3.x       | Быстрая разработка |
| Kafka          | spring-kafka          | Consumer |
| HTTP клиент    | Spring WebClient      | Неблокирующий REST |
| Мониторинг     | Actuator              | health / metrics |
| Контейнеризация| Docker                | Воспроизводимость |

---

## 6. Архитектура сервиса

### Слои

| Слой | Компоненты | Ответственность |
|------|------------|----------------|
| Kafka Listener | NotifyKafkaListener | Получение CoachResponse |
| Service | NotificationService | Идемпотентность + orchestration |
| Formatter | MessageFormatter | Формирование текста |
| Client | TelegramClient | HTTP вызов Telegram API |
| Config | KafkaConfig, TelegramConfig | Конфигурация |

---

## 7. Идемпотентность (MVP)

Kafka работает в режиме at-least-once.

Возможны повторные доставки одного и того же сообщения.

### MVP-стратегия (без БД)

- Хранить in-memory Set отправленных `recommendationId`
- При повторном получении — не отправлять повторно
- TTL хранения: 1–24 часа

Важно:  
Идентификатор идемпотентности — `recommendationId`.

---

## 8. Формирование сообщения

Пример шаблона:

Ваш финансовый совет:

{summary}

Категория: {category}

Дата: {createdAt}

Форматирование может использовать HTML (parse_mode = HTML).

---

## 9. Конфигурация

### application.yml

```yml
server:
  port: 8084

spring:
  application:
    name: notify-service

  kafka:
    bootstrap-servers: localhost:9092
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

## 10. Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/notify-service.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 11. docker-compose.services.yml (фрагмент)

```yml
services:
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

---
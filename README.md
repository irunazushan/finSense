# FinSense

<p align="center">
  <b>Гибридная AI-система для классификации транзакций и персональных финансовых рекомендаций</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue" />
  <img src="https://img.shields.io/badge/Kotlin-2.1-purple" />
  <img src="https://img.shields.io/badge/Spring_Boot-3.5-brightgreen" />
  <img src="https://img.shields.io/badge/Kafka-Event--Driven-blue" />
  <img src="https://img.shields.io/badge/PostgreSQL-18-blue" />
  <img src="https://img.shields.io/badge/Docker-ready-blue" />
  <img src="https://img.shields.io/badge/Architecture-Event--Driven-orange" />
</p>

---

## 📌 Обзор проекта

**FinSense** — это гибридная AI-архитектура для:

* Классификации банковских транзакций (ML + LLM fallback)
* Генерации персонализированных финансовых рекомендаций
* Асинхронной обработки событий

Проект демонстрирует:

* Гибридный интеллект (ML + LLM)
* Event-Driven архитектуру на базе Kafka
* Асинхронную оркестрацию микросервисов
* ReAct-подход (инструментальность AI-агентов)
* Чёткое разделение bounded contexts

Разрабатывается в рамках магистерской диссертации по гибридным AI-архитектурам.

---

## 🏗 Архитектура системы

FinSense построен на принципах **Event-Driven Microservices Architecture**.

### Основные сервисы

| Сервис                                  | Назначение                                                                |
| --------------------------------------- | ------------------------------------------------------------------------- |
| **Core Service**                        | Оркестратор, владелец доменной модели (User, Transaction, Recommendation) |
| **Classifier Service (ML)**             | Быстрая синхронная ML-классификация                                       |
| **Transaction Classifier Agent (LLM)**  | Fallback-классификация при низком confidence                              |
| **Financial Coach Agent (LLM + tools)** | Анализ расходов и генерация рекомендаций                                  |
| **Notify Service**                      | Доставка уведомлений (Telegram)                                           |
| **Kafka**                               | Центральная шина событий                                                  |
| **PostgreSQL**                          | Хранилище доменных данных                                                 |

---

## 🔄 Основные потоки обработки

### 1️⃣ Классификация транзакции

1. Generator публикует событие в `raw-transactions`
2. Core сохраняет транзакцию и вызывает ML-классификатор
3. Если `confidence ≥ threshold` → транзакция классифицирована
4. Если `confidence < threshold` → публикуется `llm-classifier-requests`
5. LLM-агент публикует `llm-classifier-responses`
6. Core обновляет статус транзакции

---

### 2️⃣ Генерация рекомендации (асинхронно)

1. Пользователь вызывает
   `POST /api/v1/users/{userId}/recommendations`
2. Core:

   * генерирует `requestId`
   * создаёт запись со статусом `PENDING`
   * публикует `coach-requests`
3. Financial Coach Agent:

   * читает транзакции (read-only)
   * выполняет инструменты анализа
   * вызывает LLM
   * обновляет запись (`COMPLETED` / `FAILED`)
   * публикует `coach-responses`
4. Notify Service:

   * читает `coach-responses`
   * отправляет сообщение в Telegram
5. Пользователь может получить результат:

   * через Telegram
   * через `GET /api/v1/recommendations/{requestId}`

---

## 📡 Kafka Topics

| Topic                      | Назначение                      |
| -------------------------- | ------------------------------- |
| `raw-transactions`         | Поток сырых транзакций          |
| `llm-classifier-requests`  | Запросы fallback-классификации  |
| `llm-classifier-responses` | Ответы LLM-классификации        |
| `coach-requests`           | Запросы генерации рекомендаций  |
| `coach-responses`          | Событие готовности рекомендации |

---

## 🧠 Архитектурные принципы

* Event-Driven Architecture
* Гибридная стратегия ML + LLM
* Асинхронная обработка тяжёлых операций
* Stateless микросервисы
* Явная state machine транзакции
* Correlation ID для трассировки
* ReAct-подход (Reason + Act через tools)
* Разделение bounded contexts

---

## 🛠 Технологический стек

* Kotlin / Java 17
* Spring Boot 3.5
* Spring Kafka
* PostgreSQL
* Liquibase
* Внешние LLM API (DeepSeek / OpenAI)
* Docker & Docker Compose

---

## 🚀 Запуск проекта

### 1️⃣ Запуск инфраструктуры

```bash
docker-compose -f docker-compose.infrastructure.yml up -d
```

### 2️⃣ Запуск сервисов

```bash
docker-compose -f docker-compose.services.yml up -d
```

### 3️⃣ Проверка состояния

Core Service:

```
http://localhost:8080/actuator/health
```

### 4️⃣ Transaction Tester (Streamlit)

Инструмент для генерации тестовых транзакций и публикации в `raw-transactions`:

```bash
docker compose -f docker-compose.services.yml --profile tester up -d tester
```

UI:

```
http://localhost:8501
```

Локальный запуск и детали: `tools/transaction-tester/README.md`

---

## 🔌 REST API (Core Service)

| Метод | Endpoint                                 |
| ----- | ---------------------------------------- |
| GET   | `/api/v1/users/{userId}/transactions`    |
| GET   | `/api/v1/users/{userId}/recommendations` |
| POST  | `/api/v1/users/{userId}/recommendations` |
| GET   | `/api/v1/recommendations/{requestId}`    |
| GET   | `/actuator/health`                       |

---

## 📂 Структура репозитория

```
/core-service
/classifier-service
/transaction-classifier-agent
/financial-coach-agent
/notify-service
/docs
/docker-compose.infrastructure.yml
/docker-compose.services.yml
```

---

## 🎯 Цели проекта

* Точность классификации ≥ 95%
* p95 времени обработки < 2 секунд
* ≤ 10% транзакций требуют LLM
* Полная трассируемость жизненного цикла транзакции
* Демонстрация гибридной AI-архитектуры в рамках магистерской диссертации

---

## 📚 Документация

Полная архитектурная документация находится в каталоге `/docs`:

* Business Case
* Architecture Drivers
* Service-level documentation
* Sequence Diagrams
* Deployment Diagram

---

## 📌 Статус

🚧 MVP в активной разработке.
Текущий фокус — стабильная реализация гибридного пайплайна и асинхронной генерации рекомендаций.

---

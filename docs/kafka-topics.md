# Kafka Topics

## Сводная таблица топиков

| Топик | Продюсер | Консьюмер | Ключ | Retention |
|-------|----------|--------------|------|-----------|
| `raw-transactions` | Generator | Core | `transactionId` | 7 дней |
| `llm-classifier-requests` | Core | Reasoning Agent | `transactionId` | 3 дня |
| `llm-classifier-responses` | Reasoning Agent | Core | `transactionId` | 3 дня |
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
  "id": "550e8400-e29b-41d4-a716-446655440000",
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
Запросы на LLM-классификацию для транзакций, с которыми ML-классификатор не справился (низкая уверенность).

**Продюсер:** Core Service  
**Консьюмеры:** Reasoning Agent  
**Ключ:** `correlationId` (UUID) – для связывания запроса и ответа.  
**Партиции:** 2  
**Replication factor:** 1  
**Retention:** 3 дня  

**Пример сообщения:**
```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "transactionData": {
    "amount": 1250.75,
    "description": "Кофе Starbucks",
    "merchantName": "Starbucks",
    "mccCode": "5812",
    "timestamp": "2026-02-17T12:34:56Z"
  },
  "context": {
    "recentTransactions": [],
    "userPreferences": {}
  }
}
```

---

### 3. `llm-classifier-responses`

**Назначение:**  
Результаты LLM-классификации от Reasoning Agent.

**Продюсер:** Reasoning Agent  
**Консьюмеры:** Core Service  
**Ключ:** `correlationId` (UUID) – тот же, что был в запросе.  
**Партиции:** 2  
**Replication factor:** 1  
**Retention:** 3 дня  

**Пример сообщения:**
```json
{
  "correlationId": "7a1e8f3c-5d2b-4a6e-9c8f-1d2e3f4a5b6c",
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "category": "food_and_drinks",
  "confidence": 0.95,
  "model": "gpt-4o",
  "latencyMs": 1250,
  "details": {
    "reasoning": "Транзакция в Starbucks, сумма небольшая, типично для категории 'Еда и напитки'."
  }
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
    "includeCategories": ["food", "transport"],
    "forceRefresh": true
  }
}
```
---

### 5. `coach-responses`

**Назначение:**  
Готовые финансовые советы, сгенерированные Coach Agent.

**Продюсер:** Coach Agent  
**Консьюмеры:** Notify Service (опционально), также может использоваться для логирования.  
**Ключ:** `userId` (UUID)  
**Партиции:** 2  
**Replication factor:** 1  
**Retention:** 3 дня  

**Пример сообщения:**
```json
{
  "recommendationId": "f1e2d3c4-b5a6-4c3d-9e8f-7a6b5c4d3e2f",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "createdAt": "2026-02-17T15:31:00Z",
  "summary": "Вы тратите на кофе 5000 руб в месяц",
  "advice": "Попробуйте готовить дома — сэкономите до 3000 руб",
  "category": "spending"
}
```

---

## Примечания по настройке Kafka

- **Bootstrap servers**: `kafka:9092` (внутри Docker-сети)
- **Количество партиций** выбрано с учётом возможного параллелизма, но может быть изменено при масштабировании.
- Все топики используют **cleanup.policy=delete** (удаление по истечении retention).
- Для разработки replication factor = 1, в продакшене рекомендуется 3.
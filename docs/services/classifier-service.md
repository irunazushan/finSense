# Classifier Service — архитектурная документация

## 1. Роль сервиса в системе

### Бизнес-назначение
Classifier Service отвечает за быструю синхронную ML-классификацию банковских транзакций. Сервис использует ONNX Runtime и модель, подготовленную в `tools/ml-training`, принимает данные транзакции через REST API и возвращает категорию вместе с числовой оценкой уверенности.

### Business capabilities
- **Классификация транзакций** — определение категории транзакции, например `FOOD_AND_DRINKS`, `TRANSPORT`, `GROCERIES`.
- **Оценка уверенности** — возврат значения `confidence` в диапазоне `0.0–1.0`, по которому Core Service принимает решение о завершении классификации или fallback на LLM-агента.
- **Подготовка признаков** — нормализация текстовых и числовых полей транзакции перед передачей в ONNX-модель.
- **Синхронный inference** — быстрый ответ в REST-потоке без обращения к Kafka или БД.

### Bounded context и данные
Сервис не владеет бизнес-данными и не хранит транзакции. Все входные данные поступают в запросе, результат возвращается синхронно. Артефакты модели (`transaction-classifier.onnx`, `labels.json`, `metadata.json`) поставляются как runtime-зависимость контейнера.

### Место в архитектуре
Classifier Service является вычислительным узлом в цепочке классификации:
- Core Service вызывает его синхронно для каждой входящей транзакции.
- Если `confidence` выше порога, Core Service сохраняет результат классификации.
- Если `confidence` ниже порога, Core Service отправляет транзакцию в `llm-classifier-requests` для обработки Transaction Classifier Agent.

---

## 2. Технологический стек

| Технология           | Обоснование                                                          |
| -------------------- | -------------------------------------------------------------------- |
| Java 17              | Стабильность, широкое распространение, производительность.           |
| Spring Boot 3.x      | Быстрая разработка REST-сервисов, встроенные механизмы конфигурации. |
| Maven                | Управление зависимостями и сборкой Java-сервиса.                     |
| Spring Web           | Создание REST-контроллера и обработка запросов.                      |
| ONNX Runtime         | Быстрый переносимый inference обученной ML-модели.                   |
| Jackson              | Обработка JSON-запросов и ответов.                                   |
| springdoc-openapi    | Автоматическая генерация OpenAPI-спецификации и Swagger UI.          |
| Spring Boot Actuator | Health checks, метрики для отладки и мониторинга.                    |
| Docker               | Изоляция, воспроизводимость окружения.                               |

---

## 3. Архитектура сервиса

### Логическая структура

| Слой               | Компоненты / пакеты                                      | Ответственность                                                            |
|--------------------|----------------------------------------------------------|----------------------------------------------------------------------------|
| **Controller**     | `com.finsense.classifier.controller.ClassifyController`  | Приём REST-запросов, валидация, возврат результата классификации.         |
| **Service**        | `com.finsense.classifier.service.ClassificationService`  | Оркестрация процесса классификации и вызов ML-стратегии.                  |
| **Model / DTO**    | `com.finsense.classifier.model.*`, `dto.*`               | Контракты входных данных, результата и категорий.                         |
| **ONNX Runtime**   | `com.finsense.classifier.onnx.*`                         | Загрузка модели, подготовка признаков, выполнение inference.              |
| **Config**         | `com.finsense.classifier.config.*`                       | Конфигурация приложения, OpenAPI, параметров модели и стратегии.          |

### Основные компоненты

| Компонент                    | Назначение                                                                    |
|------------------------------|-------------------------------------------------------------------------------|
| `ClassifyController`         | REST-эндпоинт `POST /api/classify`.                                           |
| `ClassificationService`      | Принимает входные данные, вызывает ML-классификацию и формирует ответ.        |
| `MlOnnxClassificationStrategy` | Выполняет классификацию через ONNX Runtime.                                  |
| `OnnxModelLoader`            | Загружает ONNX-модель и связанные metadata/labels.                            |
| `OnnxFeaturePreprocessor`    | Преобразует поля транзакции в feature vector для модели.                      |
| `OnnxInferenceEngine`        | Выполняет inference и возвращает вероятности/score по категориям.             |
| `TransactionCategory`        | Единый набор допустимых категорий, согласованный с Core и agent-контрактами.  |

### Поток данных внутри сервиса

1. **Вход** — `POST /api/classify` с JSON-представлением транзакции.
2. **Валидация** — проверка обязательных полей, необходимых для ML-инференса.
3. **Подготовка признаков** — нормализация текста, обработка суммы, MCC, merchant name и description.
4. **ONNX inference** — выполнение модели через ONNX Runtime.
5. **Постобработка результата** — выбор категории с максимальной оценкой и расчёт итогового `confidence`.
6. **Выход** — JSON-ответ с `transactionId`, `category`, `confidence`, `source`.

---

## 4. REST API

| Метод | URL             | Описание                    | Request Body      | Response Body          | Коды ответов  |
|-------|-----------------|-----------------------------|-------------------|------------------------|---------------|
| POST  | `/api/classify` | Классифицировать транзакцию | `ClassifyRequest` | `ClassificationResult` | 200, 400, 500 |

### Коды ответов
- `200 OK` — успешное выполнение inference.
- `400 Bad Request` — некорректные входные данные.
- `500 Internal Server Error` — внутренняя ошибка сервиса или недоступность ML-артефактов.

---

## 5. Взаимодействие с другими сервисами

### REST-взаимодействие

| Сервис       | Метод | URL             | Описание                            |
|--------------|-------|-----------------|-------------------------------------|
| Core Service | POST  | `/api/classify` | Синхронная ML-классификация транзакции |

Core Service является основным потребителем Classifier Service. Сервис не вызывает внешние API, не использует Kafka и не обращается к PostgreSQL.

---

## 6. Доменные сущности и контракты

| Сущность               | Тип               | Контекст                            | Краткое описание |
| ---------------------- | ----------------- | ----------------------------------- | ---------------- |
| `TransactionCategory`  | Enum              | Shared Contract                     | Набор допустимых категорий транзакций: `FOOD_AND_DRINKS`, `TRANSPORT`, `GROCERIES`, `RETAIL_SHOPPING`, `ENTERTAINMENT`, `HEALTH`, `BANKING_AND_FEES`, `BILLS_AND_GOVERNMENT`, `UNDEFINED`. |
| `ClassifyRequest`      | DTO               | Входной контракт API                | Представление транзакции для классификации: `transactionId`, `amount`, `description`, `merchantName`, `mccCode`, `timestamp`. |
| `ClassificationResult` | DTO               | Выходной контракт API               | Результат классификации: `transactionId`, `category`, `confidence`, `source`. |
| `OnnxModelArtifacts`   | Внутренний объект | ML Runtime Context                  | Набор артефактов модели: ONNX-файл, labels и metadata. |
| `OnnxFeatureVector`    | Внутренний объект | ML Runtime Context                  | Вектор признаков, передаваемый в ONNX Runtime. |

---

## 7. Конфигурация

### Основной `application.yml`

```yaml
server:
  port: ${SERVER_PORT:8081}

spring:
  application:
    name: classifier-service

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

app:
  classification:
    strategy: ${CLASSIFIER_STRATEGY:ml}
    model:
      dir: ${CLASSIFIER_MODEL_DIR:./model}
```

Каталог модели содержит:

```text
model/
  transaction-classifier.onnx
  labels.json
  metadata.json
```

---

## 8. Контейнеризация и запуск

### Dockerfile

```dockerfile
# ---- Builder stage ----
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn clean package -DskipTests

# ---- Runner stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /app/model
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Фрагмент `docker-compose.services.yml`

```yaml
services:
  classifier:
    build:
      context: ./classifier-service
      dockerfile: Dockerfile
    container_name: classifier-service
    ports:
      - "8081:8081"
    environment:
      SERVER_PORT: 8081
      CLASSIFIER_STRATEGY: ml
      CLASSIFIER_MODEL_DIR: /app/model
    volumes:
      - ./tools/ml-training/artifacts:/app/model:ro
    networks:
      - finsense-net
    restart: unless-stopped
```

### Порядок запуска

```bash
docker-compose -f docker-compose.services.yml up -d classifier
```

---

## 9. Связь с Python training pipeline

ML-модель готовится в `tools/ml-training`:

1. Генерируется или экспортируется датасет транзакций.
2. Выполняется обучение модели классификации.
3. Модель экспортируется в ONNX.
4. Вместе с моделью сохраняются `labels.json` и `metadata.json`.
5. Артефакты монтируются в контейнер `classifier-service` и используются во время inference.

Такой подход разделяет обучение и runtime-инференс: Python используется для подготовки модели и экспериментов, а Java/Spring Boot сервис отвечает за стабильную интеграцию модели в микросервисную архитектуру.

# Classifier Service — архитектурная документация

## 1. Роль сервиса в системе

### Бизнес-назначение
Classifier Service отвечает за быструю классификацию банковских транзакций с использованием детерминированных правил (на основе MCC-кодов, ключевых слов и регулярных выражений) с возможностью последующего расширения до ML‑модели (Smile). Сервис предоставляет синхронный REST API для Core Service.

### Business capabilities
- **Классификация транзакций** — определение категории транзакции (например, `FOOD_AND_DRINKS`, `TRANSPORT` и т.д.).
- **Оценка уверенности** — возврат числового значения `confidence` (0.0–1.0) для принятия решения в Core.
- **Нормализация текста** — приведение описаний и merchant name к единому формату для повышения точности правил.

### Bounded context и данные
Сервис **не владеет данными** — он не хранит транзакции и не ведёт свою БД. Все входные данные поступают в запросе, результат возвращается синхронно. В будущем (при внедрении ML) может потребоваться хранилище для модели, но это будет решено отдельно.

### Место в архитектуре
Classifier Service является **вычислительным узлом** (processing service) в цепочке классификации:
- Core Service вызывает его синхронно для каждой транзакции.
- Сервис не зависит от других компонентов (кроме возможного хранилища модели в будущем).

---

## 2. Технологический стек

| Компонент               | Технология                | Обоснование                                                                 |
|-------------------------|---------------------------|-----------------------------------------------------------------------------|
| Язык                    | Java 17                   | Стабильность, широкое распространение, производительность.                  |
| Фреймворк               | Spring Boot 3.x           | Быстрая разработка REST-сервисов, встроенные механизмы конфигурации.       |
| Сборка                  | Maven                     | Стандарт для Java-проектов, простота управления зависимостями.              |
| Веб (REST)              | Spring Web                | Создание контроллера и обработка запросов.                                  |
| Документация API        | springdoc-openapi         | Автоматическая генерация OpenAPI-спецификации и Swagger UI.                |
| Мониторинг              | Spring Boot Actuator      | Health checks, метрики для отладки и мониторинга.                           |
| Контейнеризация         | Docker                    | Изоляция, воспроизводимость окружения.                                      |

*Примечание:* Spring Data JPA и PostgreSQL не включены в текущую реализацию, так как сервис не использует базу данных. В будущем при необходимости хранения модели можно добавить соответствующие зависимости.

---

## 3. Архитектура сервиса

### Логическая структура (слои)

| Слой                     | Компоненты / пакеты                                      | Ответственность                                                                 |
|--------------------------|----------------------------------------------------------|---------------------------------------------------------------------------------|
| **Controller**           | `com.finsense.classifier.controller.ClassifyController`  | Приём REST-запросов, валидация, вызов сервиса классификации.                    |
| **Service**              | `com.finsense.classifier.service.ClassificationService`  | Оркестрация процесса классификации, выбор правил.                               |
| **Domain / Model**       | `com.finsense.classifier.model.*`                        | DTO для запроса/ответа, перечисление категорий.                                 |
| **Infrastructure**       | `com.finsense.classifier.rules.RuleEngine`               | Реализация правил классификации (if-else, регулярные выражения).               |
| **Config**               | `com.finsense.classifier.config.*`                       | Конфигурационные классы (CORS, OpenAPI, загрузка правил).                      |

### Основные компоненты

| Компонент                  | Назначение                                                                 |
|----------------------------|----------------------------------------------------------------------------|
| `ClassifyController`       | Единственный REST-эндпоинт `POST /api/classify`.                           |
| `ClassificationService`    | Принимает `TransactionData`, вызывает `RuleEngine`, возвращает `ClassificationResult`. |
| `RuleEngine`               | Содержит логику классификации: проверка MCC, поиск по ключевым словам, нормализация. |
| `TextNormalizer`           | Утилита для нормализации текста (toLowerCase, удаление пунктуации, стемминг опционально). |
| `TransactionCategory`      | Enum со списком допустимых категорий (включая `UNDEFINED`).                |

### Потоки данных внутри сервиса

1. **Вход** → `POST /api/classify` с `TransactionData` (JSON).
2. **Валидация** — проверка обязательных полей (amount, description).
3. **Нормализация** — `TextNormalizer` обрабатывает `description` и `merchantName`.
4. **Правила** — `RuleEngine` последовательно:
   - Проверяет `mccCode` по словарю.
   - Применяет правила по ключевым словам (регулярные выражения) для description и merchant_name тразакции.
   - Комбинирует результаты и вычисляет итоговую категорию и confidence.
5. **Формирование ответа** — `ClassificationResult` (категория, confidence, источник "RULE").
6. **Выход** — JSON-ответ.

---

## 4. REST API (контракт)

### Единственный endpoint

| Метод | URL              | Описание                                        | Request Body                     | Response Body                    | Коды ответов         |
|-------|------------------|-------------------------------------------------|----------------------------------|----------------------------------|----------------------|
| POST  | `/api/classify`  | Классифицировать транзакцию                     | `TransactionData`                | `ClassificationResult`           | 200, 400, 500        |

### Коды ответов
- `200 OK` — успешная классификация (даже если категория `UNDEFINED`).
- `400 Bad Request` — некорректные входные данные (например, отсутствует `description`).
- `500 Internal Server Error` — внутренняя ошибка (например, сбой в правилах).

*Полная спецификация доступна в Swagger UI по `/swagger-ui.html` после запуска.*


## 5. Взаимодействие с другими сервисами

### REST-взаимодействие

| Сервис              | Метод | URL          | Описание                                |
|---------------------|-------|--------------|-----------------------------------------|
| Core Service        | POST  | `/api/classify` | Вызов классификатора для транзакции     |

### Краткое описание внешних сервисов

- **Core Service** — единственный потребитель Classifier Service. Вызывает синхронно при обработке каждой транзакции.
- *Других зависимостей нет* — сервис не вызывает внешние API и не использует Kafka.

---

## 6. Доменные сущности

| Сущность               | Тип         | Контекст                  | Краткое описание                                                                 |
|------------------------|-------------|---------------------------|----------------------------------------------------------------------------------|
| `TransactionCategory`  | Enum        | Общий с Core              | Список категорий: `FOOD_AND_DRINKS`, `TRANSPORT`, `SHOPPING`, `ENTERTAINMENT`, `HEALTH`, `OTHER`, `UNDEFINED`. |
| `TransactionData`      | DTO         | Входной запрос            | Содержит `amount`, `description`, `merchantName`, `mccCode`.                    |
| `ClassificationResult` | DTO         | Выходной ответ            | Содержит `category`, `confidence`, `source`.                                    |
| `CategoryRule`         | Внутренний  | Правила классификации     | Структура: `pattern` (regex), `category`, `confidence`, `checkMerchant`, `checkDescription`. |

*Примечание:* `TransactionCategory` должен быть согласован с Core Service — значения передаются как строки в верхнем регистре.

---

## 7. Конфигурация

### Основной `application.yml`

```yaml
server:
  port: ${SERVER_PORT:8081}

spring:
  application:
    name: classifier-service

  # No database configuration — service is stateless

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

app:
  classification:
    rules-file: classpath:classifier-rules.yaml
```

В _classifier-rules.yaml_ будет хранится в виде ключа - значения? где ключом будет тип параметра и значением - категория к которому оно относится:

```yaml
mcc:
  5812: FOOD_AND_DRINKS  # рестораны
  5813: FOOD_AND_DRINKS  # бары
...

# Ключевые слова для description и merchant name
keywords:
  - category: FOOD_AND_DRINKS
    words:
      - кофе
      - кофейня
      - ресторан
...

  - category: SHOPPING
    words:
      - магазин
      - супермаркет
      - продукт
...
    
# Настройки confidence
confidence:
  mcc_base: 0.95           
  keyword_base: 0.85
  boost_per_match: 0.05 
  max: 0.99  
```

## 8. Запуск через Docker
### Dockerfile (многоступенчатая сборка)
```docker
# ---- Builder stage ----
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY classifier-service/pom.xml .
RUN mvn dependency:go-offline

COPY classifier-service/src ./src
RUN mvn clean package -DskipTests

# ---- Runner stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Фрагмент docker-compose.services.yml
```yaml
version: '3.8'

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
    networks:
      - finsense-net
    restart: unless-stopped
    # Нет зависимостей от БД или Kafka — можно запускать параллельно с Core
```

### Порядок запуска
- Запустить инфраструктуру (Postgres, Kafka) — если ещё не запущена.
- Запустить Classifier Service (он не зависит от других сервисов, кроме сети):

```bash
docker-compose -f docker-compose.services.yml up -d classifier
```

### Зависимости
Сеть finsense-net — должна быть создана (обычно в инфраструктурном compose-файле).

Никаких других зависимостей нет — сервис полностью stateless.

## 9. Стратегия эволюции к ML

- Введение интерфейса ClassificationStrategy
- Реализации:
    - RuleBasedStrategy
    - SmileModelStrategy
- Выбор через configuration

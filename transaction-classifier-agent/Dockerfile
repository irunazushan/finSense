# ---- Builder stage ----
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle :transaction-classifier-agent:bootJar --no-daemon

# ---- Runner stage ----
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=builder /app/transaction-classifier-agent/build/libs/*.jar app.jar
# Создаём директорию для логов LLM
RUN mkdir -p /var/log/finsense/llm-classifier
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
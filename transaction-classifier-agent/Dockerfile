# ---- Builder stage ----
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle bootJar --no-daemon

# ---- Runner stage ----
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
RUN mkdir -p /var/log/finsense/llm-classifier
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]

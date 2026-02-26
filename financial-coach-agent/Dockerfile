# ---- Builder stage ----
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
RUN gradle :financial-coach-agent:bootJar --no-daemon

# ---- Runner stage ----
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=builder /app/financial-coach-agent/build/libs/*.jar app.jar
RUN mkdir -p /var/log/finsense/coach-llm
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
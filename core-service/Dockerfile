# ---- Builder stage ----
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY core-service/build.gradle.kts settings.gradle.kts ./
COPY core-service/src ./src
RUN gradle :core-service:bootJar --no-daemon

# ---- Runner stage ----
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=builder /app/core-service/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
# ---- Builder stage ----
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon

# ---- Runner stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
RUN mkdir -p /var/log/finsense/coach-llm
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]

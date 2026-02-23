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
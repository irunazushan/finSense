FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/notify-service.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
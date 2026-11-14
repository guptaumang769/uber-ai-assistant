# Multi-stage build for the Uber AI assistant (Spring AI). Runs key-free with the mock model;
# set ANTHROPIC_API_KEY + SPRING_AI_MODEL_CHAT=anthropic at runtime to use a real LLM.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
# Point at the Uber platform api-gateway for live tool calls (defaults to localhost).
ENV UBER_BASE_URL="http://api-gateway:8080"
EXPOSE 8091
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8091/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

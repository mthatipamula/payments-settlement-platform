# Single container: the Spring Boot settlement API also serves the React operations console.
FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS api
WORKDIR /src
COPY gradlew settings.gradle ./
COPY gradle gradle
COPY backend backend
COPY --from=web /web/dist frontend/dist
RUN ./gradlew --no-daemon -q :backend:bootJar -PwithFrontend

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=api /src/backend/build/libs/paysettle.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

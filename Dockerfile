# Single image: builds the React app, bundles it into the Spring Boot jar, runs one service.
FROM node:20-alpine AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS api
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=api /src/target/*.jar app.jar
EXPOSE 8080
# small heap so it fits a 512 MB free instance
ENTRYPOINT ["java", "-Xmx320m", "-XX:+UseSerialGC", "-jar", "/app/app.jar"]

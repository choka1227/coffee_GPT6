FROM node:22-alpine AS frontend
WORKDIR /build/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-17 AS backend
WORKDIR /build
COPY backend/ ./backend/
COPY --from=frontend /build/frontend/dist ./frontend/dist
RUN mvn -B -ntp -f backend/pom.xml verify

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S coffee && adduser -S coffee -G coffee
WORKDIR /app
COPY --from=backend --chown=coffee:coffee /build/backend/coffee-app/target/coffee-app-0.1.0-SNAPSHOT.jar app.jar
USER coffee
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

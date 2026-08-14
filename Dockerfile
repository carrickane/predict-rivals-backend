# ---- Build stage ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

COPY gradlew ./
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew

COPY src src
RUN ./gradlew --no-daemon installDist

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/install/predict-rivals-backend/ ./

EXPOSE 8080
CMD ["bin/predict-rivals-backend"]

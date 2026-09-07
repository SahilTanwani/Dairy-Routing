# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependency resolution gets its own layer, keyed only on pom.xml. Editing a Java file
# does not invalidate it, so rebuilds skip the Maven Central download entirely.
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Tests are skipped here on purpose: MilkrouteApplicationTests needs a live Postgres,
# and the build stage has no network link to the db service. Tests run on the host
# against docker compose, not inside the image build.
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Unprivileged runtime user — nothing in this container needs root.
RUN addgroup -S milkroute && adduser -S -G milkroute milkroute

COPY --from=build /build/target/*.jar app.jar
USER milkroute

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

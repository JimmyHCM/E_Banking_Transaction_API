# ────────────────────────────────────────────────────────────────────────────
# Build stage — uses the Maven image so no local Maven / wrapper is required.
# (If you prefer a committed Maven wrapper, run `mvn -N wrapper:wrapper` and
#  switch the build stage back to `./mvnw`.)
# ────────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy the POM first so the dependency layer is cached separately from the
# source. A source-only change won't re-resolve dependencies.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# Now copy source and build.
COPY src ./src
# Tests run in CI (they need Docker for Testcontainers), not during image build.
RUN mvn -B -q clean package -DskipTests

# ────────────────────────────────────────────────────────────────────────────
# Runtime stage — slim JRE only; no build tooling in the attack surface.
# ────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Run as a non-root user (banking requirement; also limits blast radius of
# any container escape).
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

USER app

EXPOSE 8080

# -XX:MaxRAMPercentage=75.0 makes the JVM respect the container's cgroup memory
# limit instead of sizing the heap from the host's total RAM — critical in
# Kubernetes where the pod sees only its requested memory slice.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

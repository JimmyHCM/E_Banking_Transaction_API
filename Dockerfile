# ────────────────────────────────────────────────────────────────────────────
# Build stage
# ────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /app

# Copy Maven wrapper and POM first so the dependency layer is cached separately
# from the source code.  A source-only change won't re-download dependencies.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline

# Now copy source and build
COPY src ./src
# Tests run in CI, not during image build, to keep the image layer clean and fast.
RUN ./mvnw -B clean package -DskipTests

# ────────────────────────────────────────────────────────────────────────────
# Runtime stage — slim JRE only; no build tooling in the attack surface
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

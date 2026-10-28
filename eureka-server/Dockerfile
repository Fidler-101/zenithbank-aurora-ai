# ============================================================
# eureka-server — multi-stage build
# Copy this file to eureka-server/Dockerfile in your project
# (build context = that service's own folder, next to its pom.xml)
# ============================================================

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependencies separately from source so code edits don't
# force a full re-download on every rebuild.
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Run as a non-root user (least privilege)
RUN addgroup --system zenith && adduser --system --ingroup zenith zenith
COPY --from=build /build/target/*.jar app.jar
RUN chown zenith:zenith app.jar
USER zenith

EXPOSE 8761
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8761/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

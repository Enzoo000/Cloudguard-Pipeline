# Northbound Analytics — target app image.
#
# Deliberately NOT hardened against the app's own seeded vulnerabilities
# (those live in the source: LoginController, ReportService,
# ReportController, application.properties — see app/README.md). This
# Dockerfile hardens everything *around* the app instead, so that a
# Trivy scan of the built image comes back clean and the pipeline's
# pre-deploy findings map cleanly to the code/infra layers, not this one.

# --- Build stage: only this stage has Maven/JDK, none of it ships ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# --- Runtime stage: minimal JRE, no build tooling ---
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build /build/target/analytics-0.1.0.jar app.jar
# Externalized config alongside the jar (a real, common deployment
# pattern) — also what keeps the path-traversal -> hardcoded-secret
# demo in app/README.md working identically in the container: the
# secret isn't just bundled unreachably inside app.jar's classpath.
COPY src/main/resources/application.properties ./application.properties
COPY exports ./exports

RUN chown -R app:app /app
USER app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

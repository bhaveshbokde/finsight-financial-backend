# FinSight — multi-stage build
# ----------------------------
# Stage 1 compiles; stage 2 ships only a JRE and the layered application.
# The build tooling, the ~200 MB Maven cache and the source never reach the
# runtime image, which cuts both size and attack surface.

# ------------------------------------------------------------------ build --
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy the POM alone first. Docker caches each layer by the checksum of its
# inputs, so dependency resolution is only re-run when pom.xml itself changes —
# editing a Java file then rebuilds in seconds instead of re-downloading
# everything. Copying the whole context first would defeat this entirely.
# The base image already carries Maven 3.9, so the build uses it directly rather
# than the wrapper: the wrapper would have to be copied in and would then
# download a second, near-identical distribution on every cold build.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# Tests run in CI against real services (see .github/workflows/ci.yml), not here:
# the image build has no database, so a test phase would either fail or be
# limited to tests that prove little.
RUN mvn -B -q clean package -DskipTests

# Spring Boot's layertools splits the fat jar into layers ordered by how often
# they change — dependencies first, application classes last. A code-only change
# then rewrites just the final, smallest layer on push and pull.
RUN java -Djarmode=layertools -jar target/finsight-*.jar extract --destination extracted

# ---------------------------------------------------------------- runtime --
FROM eclipse-temurin:21-jre-alpine AS runtime

# Run as an unprivileged user. A container process running as root that escapes
# its namespace is root on the host; there is no reason for a web application to
# need those privileges.
RUN addgroup -S finsight && adduser -S finsight -G finsight

# wget is used by the compose healthcheck; curl would work equally well but is
# a larger package.
RUN apk add --no-cache wget tzdata

WORKDIR /app

COPY --from=build --chown=finsight:finsight /build/extracted/dependencies/ ./
COPY --from=build --chown=finsight:finsight /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=finsight:finsight /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=finsight:finsight /build/extracted/application/ ./

USER finsight

EXPOSE 8080

ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="\
-XX:MaxRAMPercentage=75.0 \
-XX:+UseG1GC \
-XX:+ExitOnOutOfMemoryError \
-Djava.security.egd=file:/dev/./urandom"
# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes its heap from
# the container's cgroup limit, so changing the container's memory allocation
# does not require changing the image.
# ExitOnOutOfMemoryError: a JVM that has exhausted its heap is not going to
# recover, and dying lets the orchestrator replace it instead of leaving a
# process that accepts connections and fails every one.

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD wget --spider -q http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

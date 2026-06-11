# Substrate self-contained image: frontend bundle + backend jar, non-root
# runtime. Built by CI (substrate-ci.yml `container` job) and consumed by the
# per-engagement deploy (infra/aws — ECS Fargate pulls the digest-pinned URI;
# see docs/deploy.md step 4). docker-compose.yml builds the same image for the
# Docker-capable testing shape.
#
# NOTE for this repo's dev machines: no Docker locally — this file is verified
# by the CI image build, not by local runs.

# ---------------------------------------------------------------------------
# Stage 1 — frontend bundle.
# Node version matches the gradle.properties nodeVersion pin (22.14.0) so the
# image build and the gradle-side C11 wiring (`gradlew :app:bootJar` outside
# Docker) produce the same bundle. vite.config.ts writes to
# ../app/src/main/resources/static, hence the /workspace layout.
# ---------------------------------------------------------------------------
FROM node:22.14.0-alpine AS frontend
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN mkdir -p /workspace/app/src/main/resources && npm run build
# bundle now at /workspace/app/src/main/resources/static

# ---------------------------------------------------------------------------
# Stage 2 — backend build (full JDK; never shipped).
# -PskipFrontendBuild: the bundle was already built in stage 1 and overlaid
# below, so the gradle-side frontend wiring (app/build.gradle C11) must not
# re-download Node inside the image build. bootJar embeds whatever sits in
# app/src/main/resources/static.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY common ./common
COPY engine ./engine
COPY ingestion ./ingestion
COPY app ./app
COPY --from=frontend /workspace/app/src/main/resources/static ./app/src/main/resources/static
RUN chmod +x gradlew && ./gradlew :app:bootJar -PskipFrontendBuild --no-daemon

# ---------------------------------------------------------------------------
# Stage 3 — runtime: JRE-only (slim) image, non-root.
# glibc (noble) over alpine/musl deliberately: this Dockerfile is only ever
# verified in CI (no local Docker), so the runtime stays on the same libc the
# test suite runs against.
# uid/gid 1001 is a CONTRACT with infra/aws/modules/service — the EFS access
# points pin posix_user/owner to 1001:1001. Change both or neither.
# data/uploads/staging are the app's filesystem roots (application.yml,
# port-map S19): EFS mounts them in the customer deploy, named volumes in
# compose, and they must pre-exist writable for plain `docker run`.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble
RUN set -eux; \
    groupadd --system --gid 1001 substrate; \
    useradd --system --uid 1001 --gid 1001 --home-dir /opt/substrate --shell /usr/sbin/nologin substrate; \
    mkdir -p /opt/substrate/data /opt/substrate/uploads /opt/substrate/staging; \
    chown -R substrate:substrate /opt/substrate; \
    # The ECS container healthCheck and the HEALTHCHECK below both shell out to
    # wget. eclipse-temurin ships it today — install only if that ever changes.
    command -v wget >/dev/null || { \
        apt-get update; \
        apt-get install -y --no-install-recommends wget; \
        rm -rf /var/lib/apt/lists/*; \
    }
USER substrate:substrate
WORKDIR /opt/substrate
COPY --from=build --chown=substrate:substrate /workspace/app/build/libs/*.jar app.jar

# Container-aware heap default; engagements override via JAVA_OPTS without
# rebuilding (task_memory is a tfvar — the percentage tracks it automatically).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

# Mirrors the ECS healthCheck (infra/aws/modules/service) and the ALB target
# check: /actuator/health is permitAll in BOTH auth modes (OidcSecurityConfig).
# start-period covers Flyway migrations on first boot against a cold database.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# sh -c + exec: JAVA_OPTS expands, java stays PID 1 for clean SIGTERM stops
# (ECS stopTimeout / compose stop both deliver SIGTERM).
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

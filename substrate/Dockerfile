# Multi-stage build: Gradle build -> slim JRE runtime.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY common ./common
COPY engine ./engine
COPY ingestion ./ingestion
COPY app ./app
RUN chmod +x gradlew && ./gradlew :app:bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 substrate
USER substrate
WORKDIR /opt/substrate
COPY --from=build /workspace/app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

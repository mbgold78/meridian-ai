package com.meridian.substrate.ingestion.e2e;

import org.springframework.test.context.TestPropertySource;

/**
 * Local fast lane of {@link AbstractDiagnosticGlacierE2e}: same single-JVM
 * ingest→diagnose flow on an isolated in-memory H2 (PostgreSQL mode) so the
 * combined ingestion+engine context wiring and the §11.3 flow are exercised
 * by every {@code gradlew build} on the Docker-less dev machine.
 *
 * <p><b>H2-green is never acceptance</b> (spec non-negotiable #7) —
 * {@link DiagnosticGlacierE2eIT} on Testcontainers-Postgres is the gate; this
 * twin exists so a context-wiring regression is caught before CI.
 *
 * <p>Own DB name: the shared {@code application-test.yml} URL
 * ({@code jdbc:h2:mem:ingestion-test}) is also used by
 * {@code IngestionPipelineE2eTest}; sharing it would cross-contaminate the
 * sha256 ingest dedupe and that suite's exact row counts.
 */
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:diagnostic-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
class DiagnosticGlacierE2eH2SmokeTest extends AbstractDiagnosticGlacierE2e {
}

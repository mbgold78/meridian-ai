package com.meridian.substrate.ingestion.e2e;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * THE §11.3 acceptance gate (engine-spec diagnostic.md §11.3/§14 test plan
 * #13; ADR-004 D14): the full single-JVM ingest→diagnose pipeline of
 * {@link AbstractDiagnosticGlacierE2e} against real Postgres 16 under
 * {@code ddl-auto=validate} + the full Flyway chain (incl. the V7_1
 * append-only trigger and active-run partial index).
 *
 * <p>Self-disables without Docker ({@code disabledWithoutDocker = true}) —
 * compiles locally on the Docker-less dev machine, RUNS in CI. H2-green is
 * never acceptance (spec non-negotiable #7); claiming local verification of
 * this gate would be a false-PASS.
 */
@Testcontainers(disabledWithoutDocker = true)
class DiagnosticGlacierE2eIT extends AbstractDiagnosticGlacierE2e {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
}

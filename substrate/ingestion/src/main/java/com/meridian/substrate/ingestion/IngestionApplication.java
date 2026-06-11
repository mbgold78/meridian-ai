package com.meridian.substrate.ingestion;

import com.meridian.substrate.common.governance.UngovernedModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Operator-side ingestion runner: customer data (CSV/JSON/text) -> canonical entities.
 * Runs as a standalone CLI against the engagement database; the app module stays
 * deployable without it ("whole or in part").
 *
 * <p>The JVM exit code is the CLI's {@code ExitCodeGenerator} value
 * (0 success / 1 errors / 2 usage / 3 zero-yield) — port-map §2.4 "non-zero
 * exit codes on failure"; homepoc always exited 0.
 *
 * <p><b>Contract-gateway exemption (ADR-002 D3, declared not discovered):</b>
 * the {@code @UngovernedModule} marker below is the in-code half of the D3
 * documentation requirement; the §14.6 ArchUnit suite pins both its presence
 * and the reason string, and separately proves this module never imports
 * {@code engine} (the gateway lives there — the exemption is structural, not
 * just declared).
 */
@UngovernedModule(reason = IngestionApplication.UNGOVERNED_REASON)
@SpringBootApplication(scanBasePackages = "com.meridian.substrate")
@EntityScan(basePackages = "com.meridian.substrate")
@EnableJpaRepositories(basePackages = "com.meridian.substrate")
public class IngestionApplication {

    /**
     * ADR-002 D3 reason string, verbatim intent: ingestion's bulk-commit path
     * writes canonical entities without transiting app/engine services and is
     * operator-curated, pre-activation engagement data.
     */
    public static final String UNGOVERNED_REASON =
            "Ingestion bulk-commit writes operator-curated, pre-activation engagement data"
                    + " without transiting app/engine services; declared exempt from the contract"
                    + " enforcement gateway in v1 (ADR-002 D3). MUST be re-evaluated for gateway"
                    + " coverage before Phase 4 service-mode ingest ships.";

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(IngestionApplication.class, args)));
    }
}

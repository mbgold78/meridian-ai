package com.meridian.substrate.ingestion;

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
 */
@SpringBootApplication(scanBasePackages = "com.meridian.substrate")
@EntityScan(basePackages = "com.meridian.substrate")
@EnableJpaRepositories(basePackages = "com.meridian.substrate")
public class IngestionApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(IngestionApplication.class, args)));
    }
}

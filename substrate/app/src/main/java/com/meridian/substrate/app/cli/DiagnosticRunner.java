package com.meridian.substrate.app.cli;

import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.engine.diagnostic.DiagnosticProperties;
import com.meridian.substrate.engine.diagnostic.DiagnosticRunService;
import com.meridian.substrate.engine.diagnostic.exception.DiagnosticConflictException;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The diagnostic CLI (engine-spec diagnostic.md §10.3 — EvalRunner /
 * IngestionCommand idiom). Activated by {@code substrate.diagnostic.run=true};
 * resolves the domain by name and executes ONE synchronous run through the
 * SAME governed services as the REST surface — no {@code @Ungoverned}
 * anywhere: diagnostics run on possibly-ACTIVE engagements, unlike the D3
 * ingestion exemption (ADR-004 D12).
 *
 * <p><b>Documented command</b> (mock provider — no API key needed):
 * <pre>
 * gradlew :app:bootRun --args="--spring.profiles.active=dev-shared --substrate.diagnostic.run=true \
 *   --substrate.diagnostic.domain-name=Glacier --substrate.diagnostic.required-min-findings=1 \
 *   --substrate.llm.provider=mock"
 * </pre>
 *
 * <p><b>Exit codes</b> (mirrors EvalRunner; spec §10.3 — no-silent-zero-yield):
 * <ul>
 *   <li>{@code 0} — ran and yield ok: COMPLETED or COMPLETED_WITH_ERRORS with
 *       the yield requirement met, INCLUDING the legitimate
 *       {@code COMPLETED yield=0} case when required-min-findings=0 (always
 *       accompanied by the explicit {@code DIAG_EVENT kind=yield} line);</li>
 *   <li>{@code 2} — run FAILED (incl. {@code NO_CANDIDATES}) or did not run
 *       to completion (cancelled mid-run / start conflict);</li>
 *   <li>{@code 3} — HARD FAIL {@code ZERO_YIELD} ({@code ALL_FILTERED} /
 *       {@code BELOW_REQUIRED});</li>
 *   <li>{@code 4} — misconfiguration: runner enabled without
 *       {@code domain-name}, or the named domain does not exist.</li>
 * </ul>
 *
 * <p>Structured {@code DIAG_EVENT} lines (run_started / batch_completed /
 * batch_failed / run_finalized / zero_yield / yield) are emitted by the
 * engine services themselves; this runner adds the misconfiguration and
 * cli_exit lines.
 */
@Component
@ConditionalOnProperty(name = "substrate.diagnostic.run", havingValue = "true", matchIfMissing = false)
public class DiagnosticRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticRunner.class);

    static final int EXIT_OK = 0;
    static final int EXIT_RUN_FAILED = 2;
    static final int EXIT_ZERO_YIELD = 3;
    static final int EXIT_MISCONFIGURATION = 4;

    static final String CLI_ACTOR_LABEL = "cli:diagnostic-runner";

    private final DiagnosticRunService runService;
    private final DomainRepository domainRepository;
    private final DiagnosticProperties properties;
    private final ConfigurableApplicationContext context;

    public DiagnosticRunner(DiagnosticRunService runService,
                            DomainRepository domainRepository,
                            DiagnosticProperties properties,
                            ConfigurableApplicationContext context) {
        this.runService = runService;
        this.domainRepository = domainRepository;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = execute();
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    /**
     * Runs once and computes the exit code without terminating the JVM —
     * separated from {@link #run} for testability (test plan #12, the
     * IngestionCommandExitCodeTest idiom).
     */
    int execute() {
        String domainName = properties.getDomainName();
        if (domainName == null || domainName.isBlank()) {
            log.error("DIAG_EVENT kind=misconfiguration exit={} hint='substrate.diagnostic.run=true"
                    + " requires substrate.diagnostic.domain-name'", EXIT_MISCONFIGURATION);
            return EXIT_MISCONFIGURATION;
        }
        Optional<Domain> domain = domainRepository.findFirstByName(domainName.trim());
        if (domain.isEmpty()) {
            log.error("DIAG_EVENT kind=misconfiguration exit={} hint='unknown domain \"{}\" —"
                    + " ingest/seed it first (curl :8080/api/v1/domains lists known domains)'",
                    EXIT_MISCONFIGURATION, domainName);
            return EXIT_MISCONFIGURATION;
        }

        try {
            DiagnosticRunService.StartResult started = runService.startRun(
                    new DiagnosticRunService.StartCommand(
                            domain.get().getId(),
                            null, null, null,
                            properties.getRequiredMinFindings(),
                            null,
                            false,
                            "cli-" + UUID.randomUUID(),
                            CLI_ACTOR_LABEL,
                            true /* the CLI run is ALWAYS synchronous (spec §10.3) */));
            DiagnosticRunDetail detail = runService.getRun(started.runId());
            int exitCode = exitCodeFor(detail.status());
            log.info("DIAG_EVENT kind=cli_exit runId={} status={} committed={} exit={}",
                    detail.runId(), detail.status(), detail.findingsCommitted(), exitCode);
            return exitCode;
        } catch (DiagnosticConflictException activeRun) {
            log.error("DIAG_EVENT kind=run_conflict exit={} error='{}'",
                    EXIT_RUN_FAILED, activeRun.getMessage());
            return EXIT_RUN_FAILED;
        }
    }

    static int exitCodeFor(String status) {
        return switch (status) {
            case DiagnosticRunService.STATUS_COMPLETED,
                 DiagnosticRunService.STATUS_COMPLETED_WITH_ERRORS -> EXIT_OK;
            case DiagnosticRunService.STATUS_ZERO_YIELD -> EXIT_ZERO_YIELD;
            // FAILED, CANCELLED (cancel is neutral as a product outcome, but a
            // CLI invocation that did not run to completion must not exit 0),
            // and any unexpected non-terminal state.
            default -> EXIT_RUN_FAILED;
        };
    }
}

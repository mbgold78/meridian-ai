package com.meridian.substrate.app.cli;

import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.engine.ai.client.MockAnthropicClient;
import com.meridian.substrate.engine.diagnostic.DiagnosticProperties;
import com.meridian.substrate.engine.diagnostic.DiagnosticRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test plan #12 — the §10.3 CLI exit-code matrix
 * (IngestionCommandExitCodeTest idiom: drive {@code execute()} directly and
 * assert the returned code; {@code run()} is the only System.exit path and is
 * never invoked here):
 *
 * <ul>
 *   <li>{@code 0} — ran-and-yield-ok (committed ≥ required), AND the
 *       legitimate {@code COMPLETED yield=0} case with
 *       required-min-findings=0 (all-filtered, explicit yield event);</li>
 *   <li>{@code 2} — run FAILED ({@code NO_CANDIDATES}: zero parseable
 *       candidates is ALWAYS a hard fail — "command exited 0" is never
 *       "product worked");</li>
 *   <li>{@code 3} — HARD FAIL {@code ZERO_YIELD} (all-filtered with
 *       required>0);</li>
 *   <li>{@code 4} — misconfiguration: unknown domain; runner without
 *       {@code domain-name}.</li>
 * </ul>
 *
 * <p>Mock provider with the opt-in diagnostic script (default OFF — pinned
 * Phase-2A mock behavior untouched); {@code sync=true} so the CLI run is the
 * documented synchronous lane.
 */
@SpringBootTest(properties = {
        "substrate.llm.provider=mock",
        "substrate.diagnostic.sync=true"
})
@ActiveProfiles("dev")
@Transactional
class DiagnosticRunnerExitCodeTest {

    @Autowired DiagnosticRunService runService;
    @Autowired DomainRepository domainRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired DiagnosticProperties properties;
    @Autowired MockAnthropicClient mockClient;

    private DiagnosticRunner runner;
    private String domainName;

    @BeforeEach
    void fixture() {
        // context deliberately null: execute() never exits the JVM; run() does.
        runner = new DiagnosticRunner(runService, domainRepository, properties, null);
        domainName = "cli-exit " + UUID.randomUUID();
        Domain domain = new Domain();
        domain.setName(domainName);
        domain.setCreatedBy("test");
        domainRepository.save(domain);
    }

    @AfterEach
    void restoreProperties() {
        properties.setDomainName(null);
        properties.setRequiredMinFindings(0);
        mockClient.setDiagnosticScript("");
    }

    @Test
    void exit0_committedMeetsRequired() {
        mockClient.setDiagnosticScript(MockAnthropicClient.SCRIPT_STRUCTURAL);
        properties.setDomainName(domainName);
        properties.setRequiredMinFindings(1);

        assertThat(runner.execute()).isEqualTo(DiagnosticRunner.EXIT_OK);
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(
                domainRepository.findFirstByName(domainName).orElseThrow().getId()))
                .isNotEmpty();
    }

    @Test
    void exit0_legitimateCompletedYieldZero_whenRequiredMinFindingsIsZero() {
        // Candidates produced, ALL filtered, required=0 → COMPLETED + explicit
        // yield event → exit 0 (spec §10.2 lane 2 / §10.3).
        mockClient.setDiagnosticScript(MockAnthropicClient.SCRIPT_UNGROUNDED_CONTENT);
        properties.setDomainName(domainName);
        properties.setRequiredMinFindings(0);

        assertThat(runner.execute()).isEqualTo(DiagnosticRunner.EXIT_OK);
    }

    @Test
    void exit2_runFailedOnNoCandidates() {
        // default mock prose → zero parseable candidates → FAILED NO_CANDIDATES
        properties.setDomainName(domainName);
        properties.setRequiredMinFindings(1);

        assertThat(runner.execute()).isEqualTo(DiagnosticRunner.EXIT_RUN_FAILED);
    }

    @Test
    void exit3_zeroYieldHardFail() {
        mockClient.setDiagnosticScript(MockAnthropicClient.SCRIPT_UNGROUNDED_CONTENT);
        properties.setDomainName(domainName);
        properties.setRequiredMinFindings(1);

        assertThat(runner.execute()).isEqualTo(DiagnosticRunner.EXIT_ZERO_YIELD);
    }

    @Test
    void exit4_unknownDomain() {
        properties.setDomainName("no-such-domain-" + UUID.randomUUID());

        assertThat(runner.execute()).isEqualTo(DiagnosticRunner.EXIT_MISCONFIGURATION);
    }

    @Test
    void exit4_runnerWithoutDomainName() {
        properties.setDomainName(null);

        assertThat(runner.execute()).isEqualTo(DiagnosticRunner.EXIT_MISCONFIGURATION);
    }

    @Test
    void exitCodeMapping_isPinnedPerStatus() {
        assertThat(DiagnosticRunner.exitCodeFor(DiagnosticRunService.STATUS_COMPLETED)).isZero();
        assertThat(DiagnosticRunner.exitCodeFor(DiagnosticRunService.STATUS_COMPLETED_WITH_ERRORS)).isZero();
        assertThat(DiagnosticRunner.exitCodeFor(DiagnosticRunService.STATUS_ZERO_YIELD))
                .isEqualTo(DiagnosticRunner.EXIT_ZERO_YIELD);
        assertThat(DiagnosticRunner.exitCodeFor(DiagnosticRunService.STATUS_FAILED))
                .isEqualTo(DiagnosticRunner.EXIT_RUN_FAILED);
        assertThat(DiagnosticRunner.exitCodeFor(DiagnosticRunService.STATUS_CANCELLED))
                .isEqualTo(DiagnosticRunner.EXIT_RUN_FAILED);
    }
}

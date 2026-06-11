package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.repo.EvidenceRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;

/**
 * Default beans for the diagnostic engine (engine-spec diagnostic.md §1/§4.2).
 * Component-scanned by the app module; the engine test context
 * ({@code EngineTestApplication}) picks it up via its
 * {@code engine.diagnostic} scan entry.
 */
@Configuration
public class DiagnosticEngineConfig {

    /**
     * Every diagnostic service takes the Spring {@link Clock} bean (S15 —
     * spec §1 entity conventions). Same {@code @ConditionalOnMissingBean}
     * posture as {@code CascadeEngineConfig}: in the full app both configs
     * coexist (first one wins); test contexts that scan only
     * {@code engine.diagnostic} still get a clock; DemoPack / fixed-clock
     * configs substitute their own.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock diagnosticClock() {
        return Clock.systemUTC();
    }

    /**
     * The bounded run executor (spec §4.2 step 4): batches run SEQUENTIALLY
     * within a run (rate-limit friendly), and runs are human-paced — a single
     * worker with a small queue is deliberate backpressure, not a bottleneck.
     * {@code substrate.diagnostic.sync=true} bypasses it entirely (tests +
     * the CLI runner execute inline).
     */
    @Bean(name = "diagnosticRunExecutor")
    public ThreadPoolTaskExecutor diagnosticRunExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("diag-run-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.initialize();
        return executor;
    }

    /** EXISTENCE_ONLY is the only v1 verifier (spec §6.2; EXTENSION POINT(diagnostic-1) seam). */
    @Bean
    @ConditionalOnMissingBean(GroundingVerifier.class)
    public GroundingVerifier existenceGroundingVerifier(EvidenceRepository evidenceRepository) {
        return new ExistenceGroundingVerifier(evidenceRepository);
    }

    /** No-op attestation seam default (ADR-004 D12); a real hook bean replaces it. */
    @Bean
    @ConditionalOnMissingBean(DiagnosticAttestationHook.class)
    public DiagnosticAttestationHook diagnosticAttestationHook() {
        return new NoopDiagnosticAttestationHook();
    }
}

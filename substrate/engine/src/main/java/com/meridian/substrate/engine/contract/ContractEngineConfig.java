package com.meridian.substrate.engine.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.engine.contract.monitor.HttpMcpLivenessProbe;
import com.meridian.substrate.engine.contract.monitor.McpLivenessProbe;
import com.meridian.substrate.engine.contract.monitor.SweepJitter;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Default beans for the contract engine. Component-scanned by the app module
 * ({@code scanBasePackages = "com.meridian.substrate"}).
 *
 * <p>The injectable {@code java.time.Clock} bean every contract service takes
 * (spec §1, demo determinism per S15) is the shared engine clock declared in
 * {@code CascadeEngineConfig} ({@code @ConditionalOnMissingBean} — DemoPack
 * and tests substitute fixed clocks); no second clock bean is declared here.
 */
@Configuration
public class ContractEngineConfig {

    /**
     * The REAL ADR D14 mirror (3b-2) — enabled now that the pinned
     * hash-vector suite ({@code ContractMirrorHashVectorTest}) freezes the
     * anchor layout (port-map trap #3 gate). {@code @ConditionalOnMissingBean}
     * retained so tests substitute recording fakes;
     * {@link NoopContractAccountabilityMirror} remains available as an
     * explicit opt-out bean.
     */
    @Bean
    @ConditionalOnMissingBean(ContractAccountabilityMirror.class)
    public ContractAccountabilityMirror contractAccountabilityMirror(
            DecisionLogService decisionLogService, Clock clock, ObjectMapper objectMapper) {
        return new DecisionLogContractAccountabilityMirror(decisionLogService, clock, objectMapper);
    }

    /**
     * Liveness-only MCP probe default for the §5.3 MCP_PROBE evaluator
     * (ADR D11; a seam so tests substitute fakes).
     */
    @Bean
    @ConditionalOnMissingBean(McpLivenessProbe.class)
    public McpLivenessProbe mcpLivenessProbe() {
        return new HttpMcpLivenessProbe();
    }

    /**
     * §5.2 scheduling jitter default ("next cadence boundary + jitter ≤ 60 s")
     * — random 0–60 000 ms; tests substitute {@code SweepJitter.none()}.
     */
    @Bean
    @ConditionalOnMissingBean(SweepJitter.class)
    public SweepJitter sweepJitter() {
        return () -> ThreadLocalRandom.current().nextLong(0, 60_001);
    }
}

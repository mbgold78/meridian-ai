package com.meridian.substrate.engine.cascade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Default beans for the cascade engine. Component-scanned by the app module
 * ({@code scanBasePackages = "com.meridian.substrate"}).
 *
 * <p>{@code @EnableScheduling} arms the {@link CascadeEscalationSweeper}'s
 * hourly {@code @Scheduled} sweep (engine-spec cascade.md §9) — the first
 * scheduled task in the substrate app. The engine {@code @DataJpaTest} slices
 * are unaffected (no component scan there); the sweeper is idempotent by
 * construction, so an in-test cron fire is harmless by design.
 */
@Configuration
@EnableScheduling
public class CascadeEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(CascadeEngineConfig.class);

    /**
     * Every engine cascade service takes the Spring {@link Clock} bean
     * (engine-spec cascade.md §1: dev/prod = system clock; DemoPack and tests
     * inject fixed clocks — fixture seam S15). {@code @ConditionalOnMissingBean}
     * lets the DemoPack/test contexts substitute their own.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock cascadeClock() {
        return Clock.systemUTC();
    }

    /**
     * No-op attestation seam default (ADR D15); a real hook bean replaces it.
     *
     * <p>This bean method only runs when NO real hook bean exists — exactly
     * the condition under which {@code substrate.cascade.attest-to-decision-log}
     * is INERT (the flag is a reserved extension point read by nothing; the
     * property value cannot change which bean is wired). A true flag with the
     * no-op hook would otherwise silently fake a tamper-evidence posture, so
     * it WARNs loudly here (the DemoFixedClockConfig idiom).
     */
    @Bean
    @ConditionalOnMissingBean(CascadeAttestationHook.class)
    public CascadeAttestationHook cascadeAttestationHook(CascadeProperties properties) {
        if (properties.isAttestToDecisionLog()) {
            log.warn("substrate.cascade.attest-to-decision-log=true but NO CascadeAttestationHook"
                    + " bean is present — the flag is INERT (reserved extension point, ADR-001"
                    + " D15): cascade resolutions are NOT being anchored to the decision log."
                    + " Remove the flag or contribute a real hook bean.");
        }
        return new NoopCascadeAttestationHook();
    }
}

package com.meridian.substrate.engine.cascade;

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

    /** No-op attestation seam default (ADR D15); a real hook bean replaces it. */
    @Bean
    @ConditionalOnMissingBean(CascadeAttestationHook.class)
    public CascadeAttestationHook cascadeAttestationHook() {
        return new NoopCascadeAttestationHook();
    }
}

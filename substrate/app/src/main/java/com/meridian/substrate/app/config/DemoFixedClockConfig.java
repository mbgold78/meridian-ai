package com.meridian.substrate.app.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The injected-fixed-clock DEMO/ACCEPTANCE lever (engine-spec contracts.md
 * §13 row 3b; fixture seam S15 demo determinism): when
 * {@code substrate.demo.fixed-clock=<ISO-8601 instant>} is set, the shared
 * engine {@code java.time.Clock} bean is pinned to that instant — e.g.
 *
 * <pre>
 * gradlew :app:bootRun --args="--spring.profiles.active=dev \
 *   --substrate.demo.seed=true --substrate.demo.contracts.activate=true \
 *   --substrate.demo.fixed-clock=2026-05-12T00:00:00Z"
 * </pre>
 *
 * pins "now" past 2026-05-08 (April period end + 7 grace days), so the §13 3b
 * acceptance sequence (run-now → BREACH_DETECTED → attest → COMPLIANT →
 * decline → signals) is reproducible byte-for-byte.
 *
 * <p>{@code @Primary} beats the {@code @ConditionalOnMissingBean} system-UTC
 * default in {@code CascadeEngineConfig} regardless of configuration-class
 * processing order. Period-key ZONE math still follows
 * {@code substrate.contracts.clock.zone} (the evaluators zone the instant
 * themselves); the bean itself is UTC-pinned. Absent the property, nothing
 * changes — never set it in a customer engagement build.
 */
@Configuration
public class DemoFixedClockConfig {

    private static final Logger log = LoggerFactory.getLogger(DemoFixedClockConfig.class);

    @Bean
    @Primary
    @ConditionalOnProperty(name = "substrate.demo.fixed-clock")
    public Clock demoFixedClock(@Value("${substrate.demo.fixed-clock}") String isoInstant) {
        Instant fixedAt = Instant.parse(isoInstant.trim());
        log.warn("DEMO LEVER ACTIVE: engine clock FIXED at {} (substrate.demo.fixed-clock; "
                + "S15 demo determinism — never enable in a customer engagement build)", fixedAt);
        return Clock.fixed(fixedAt, ZoneOffset.UTC);
    }
}

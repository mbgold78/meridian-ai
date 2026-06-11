package com.meridian.substrate.common.domain.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PINNED wire-status projection contract (engine-spec agents.md §5.2; test
 * plan #2; ADR-003 D4) — lands BEFORE any OperationsTab swap. Pins:
 *
 * <ol>
 *   <li>the precedence table: retired &gt; paused &gt; intervention-required
 *       &gt; shadow/live;</li>
 *   <li>the serialization literals = the four fixture pill keys + the
 *       additive {@code retired} (Mike-flagged, default-excluded from fleet
 *       lists);</li>
 *   <li>an in-intervention LIVE agent projects {@code intervention-required}
 *       while its machine state stays LIVE (the two-dimensional model — the
 *       No-Show Predictor is still running);</li>
 *   <li>no agent enum value serializes error/failure vocabulary (spec
 *       non-negotiable #2 / invariant I5 enum half).</li>
 * </ol>
 *
 * <p>Fixture truth: {@code AgentStatus} union in
 * {@code C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/types.ts}
 * — {@code 'live' | 'shadow' | 'paused' | 'intervention-required'} — copied
 * 2026-06-11 (contracts §14.1 discipline). {@code intervention-required} is
 * the fixture literal, NOT the port-map prose name {@code in-intervention}.
 */
class AgentWireStatusProjectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------- 1. pinned precedence

    @ParameterizedTest(name = "mode={0}, openInterventions={1} -> {2}")
    @CsvSource({
            // retired wins over EVERYTHING — even with open interventions
            "RETIRED, 0, RETIRED",
            "RETIRED, 3, RETIRED",
            // paused wins over interventions (else-if ladder, §5.2)
            "PAUSED, 0, PAUSED",
            "PAUSED, 2, PAUSED",
            // open intervention beats shadow/live
            "LIVE, 1, INTERVENTION_REQUIRED",
            "LIVE, 5, INTERVENTION_REQUIRED",
            "SHADOW, 1, INTERVENTION_REQUIRED",
            // else the operational mode passes through
            "LIVE, 0, LIVE",
            "SHADOW, 0, SHADOW",
    })
    void pinnedPrecedence(AgentOperationalMode mode, int openInterventions, AgentWireStatus expected) {
        assertThat(AgentWireStatus.project(mode, openInterventions)).isEqualTo(expected);
    }

    /** The two-dimensional model: in-intervention LIVE agent is STILL LIVE underneath. */
    @Test
    void inInterventionLiveAgentProjectsInterventionRequiredWhileModeStaysLive() {
        AgentOperationalMode mode = AgentOperationalMode.LIVE;
        assertThat(AgentWireStatus.project(mode, 1)).isEqualTo(AgentWireStatus.INTERVENTION_REQUIRED);
        // the machine state is untouched by the projection — pause is a separate evented act
        assertThat(mode).isEqualTo(AgentOperationalMode.LIVE);
        assertThat(mode.wireValue()).isEqualTo("live");
    }

    // ------------------------------------- 2. exact fixture pill vocabulary

    @Test
    void serializationLiteralsAreTheFixturePillKeysPlusAdditiveRetired() throws Exception {
        // the four fixture pill keys, byte-identical to types.ts (copied 2026-06-11)
        assertThat(MAPPER.writeValueAsString(AgentWireStatus.LIVE)).isEqualTo("\"live\"");
        assertThat(MAPPER.writeValueAsString(AgentWireStatus.SHADOW)).isEqualTo("\"shadow\"");
        assertThat(MAPPER.writeValueAsString(AgentWireStatus.PAUSED)).isEqualTo("\"paused\"");
        assertThat(MAPPER.writeValueAsString(AgentWireStatus.INTERVENTION_REQUIRED))
                .as("the fixture literal — NOT 'in-intervention' (known doc drift, ADR-003)")
                .isEqualTo("\"intervention-required\"");
        // the additive fifth value (Mike-flagged, ADR D4): excluded from fleet
        // lists by default — the swapped pill map crashes on unknown values
        assertThat(MAPPER.writeValueAsString(AgentWireStatus.RETIRED)).isEqualTo("\"retired\"");
        assertThat(AgentWireStatus.values()).hasSize(5);
    }

    @Test
    void operationalModeSerializesKebabFixtureVocabulary() throws Exception {
        assertThat(MAPPER.writeValueAsString(AgentOperationalMode.SHADOW)).isEqualTo("\"shadow\"");
        assertThat(MAPPER.writeValueAsString(AgentOperationalMode.LIVE)).isEqualTo("\"live\"");
        assertThat(MAPPER.writeValueAsString(AgentOperationalMode.PAUSED)).isEqualTo("\"paused\"");
        assertThat(MAPPER.writeValueAsString(AgentOperationalMode.RETIRED)).isEqualTo("\"retired\"");
    }

    // ------------------- 3. no error/failure vocabulary in ANY agent enum

    @Test
    void noAgentEnumValueSerializesErrorOrFailureVocabulary() {
        List<Class<? extends Enum<?>>> agentEnums = List.of(
                AgentOperationalMode.class, AgentWireStatus.class, AgentEventType.class,
                AgentExecutionLocus.class, AgentControlEffect.class,
                AgentControlConfirmationState.class, AgentSightingChannel.class,
                AgentObservability.class, AgentStaleness.class,
                AgentInterventionStatus.class, AgentInterventionTrigger.class,
                AgentInterventionResolution.class, AgentReviewKind.class,
                AgentReviewDecision.class, AgentGateRuleType.class, AgentGateStanding.class,
                AgentReadinessSource.class, AgentSupervisoryModel.class,
                AgentRetiredReason.class, AgentNotificationType.class);

        assertThat(agentEnums).hasSize(20);   // the full §3 vocabulary, none forgotten
        for (Class<? extends Enum<?>> enumType : agentEnums) {
            for (Enum<?> value : enumType.getEnumConstants()) {
                String wire = value.name().toLowerCase().replace('_', '-');
                assertThat(wire)
                        .as("%s.%s must not carry error/failure vocabulary (non-negotiable #2)",
                                enumType.getSimpleName(), value.name())
                        .doesNotContain("error")
                        .doesNotContain("fail")
                        .doesNotContain("fatal");
            }
        }
    }
}

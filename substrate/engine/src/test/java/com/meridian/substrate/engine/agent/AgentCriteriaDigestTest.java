package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code criteria_digest} canonical-vector pin (engine-spec agents.md §9.1;
 * ADR-003 D5; test plan #11 digest half) — lands BEFORE the 6b chain mirror
 * enables (port-map trap #3). The digest anchors the DEPLOYED chain entry:
 * any change to the canonical serialization is a chain-verification break and
 * must be a reviewed migration, which is exactly what the pinned vector
 * enforces.
 *
 * <p>Vector source: the polished Curasync {@code a-pre-visit} mandate from
 * {@code C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/subsidiaries/healthcare.ts}
 * (copied 2026-06-11; spec §9.1's own worked example).
 */
class AgentCriteriaDigestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The fixture mandate, VERBATIM from healthcare.ts (copied 2026-06-11). */
    private static final String A_PRE_VISIT_CRITERIA =
            "Pre-fill intake forms from prior-visit data, target 90% pre-fill accuracy";

    @Test
    void pinnedDigestVector() throws Exception {
        String digest = AgentCriteriaDigest.compute(A_PRE_VISIT_CRITERIA, List.of(
                new AgentCriteriaDigest.GateInput(
                        "target 90% pre-fill accuracy",
                        "STRUCTURED",
                        MAPPER.readTree("{\"metric\":\"prefill_accuracy\",\"gte\":0.90}"),
                        "MONTHLY")));

        // PINNED: SHA-256 of the §9.1 canonical serialization. A failure here
        // means the canonical layout changed — that breaks chain verification
        // for every previously anchored DEPLOYED entry; do NOT update the
        // vector without a reviewed migration story.
        assertThat(digest).isEqualTo(
                "fd3d735c884b6608151bcd46b32b5eea8bf8ec6b9965127b8907eea4776b7aef");
    }

    @Test
    void digestIsDeterministicAndShaped() throws Exception {
        List<AgentCriteriaDigest.GateInput> gates = List.of(
                new AgentCriteriaDigest.GateInput("target 95% routing accuracy", "STRUCTURED",
                        MAPPER.readTree("{\"metric\":\"routing_accuracy\",\"gte\":0.95}"), "MONTHLY"),
                new AgentCriteriaDigest.GateInput("zero PHI leakage", "UNSTRUCTURED", null, "QUARTERLY"));

        String first = AgentCriteriaDigest.compute("Route inbound patient messages", gates);
        String second = AgentCriteriaDigest.compute("Route inbound patient messages", gates);

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void gateOrderProseAndEveryGateFieldAreDigestSignificant() throws Exception {
        AgentCriteriaDigest.GateInput g1 = new AgentCriteriaDigest.GateInput(
                "clause A", "STRUCTURED", MAPPER.readTree("{\"metric\":\"m\",\"gte\":0.9}"), "MONTHLY");
        AgentCriteriaDigest.GateInput g2 = new AgentCriteriaDigest.GateInput(
                "clause B", "UNSTRUCTURED", null, null);

        String base = AgentCriteriaDigest.compute("prose", List.of(g1, g2));

        assertThat(AgentCriteriaDigest.compute("prose", List.of(g2, g1)))
                .as("ordered gate rows — order is part of the frozen mandate").isNotEqualTo(base);
        assertThat(AgentCriteriaDigest.compute("other prose", List.of(g1, g2)))
                .isNotEqualTo(base);
        assertThat(AgentCriteriaDigest.compute("prose", List.of(
                new AgentCriteriaDigest.GateInput("clause A", "STRUCTURED",
                        MAPPER.readTree("{\"metric\":\"m\",\"gte\":0.95}"), "MONTHLY"), g2)))
                .as("predicate content is digest-significant").isNotEqualTo(base);
        assertThat(AgentCriteriaDigest.compute("prose", List.of(
                new AgentCriteriaDigest.GateInput("clause A", "STRUCTURED",
                        MAPPER.readTree("{\"metric\":\"m\",\"gte\":0.9}"), "WEEKLY"), g2)))
                .as("cadence is digest-significant").isNotEqualTo(base);
    }
}

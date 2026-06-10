package com.meridian.substrate.common.domain.enums;

/**
 * Provenance of an {@link com.meridian.substrate.common.domain.Assertion}: who or
 * what produced the claim.
 *
 * <p>All five values are present in the schema from Phase 2A so that later
 * phases can persist HUMAN- and AGENT-produced assertions through the same
 * Evidence chain without a migration. Phase 2A only writes the three AI values;
 * HUMAN lands when the Capability Designer's commit step records a user-edited
 * draft (Phase 2B+), and AGENT lands when the Agent Architecture (Phase 6) ships.
 */
public enum ProducedBy {
    AI_GENERATIVE,
    AI_ASSISTIVE,
    AI_VALIDATIVE,
    HUMAN,
    AGENT
}

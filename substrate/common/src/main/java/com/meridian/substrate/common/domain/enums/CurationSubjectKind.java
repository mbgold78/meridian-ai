package com.meridian.substrate.common.domain.enums;

/**
 * Subject discriminator for the unified {@code ai_curation_events} audit
 * (engine-spec diagnostic.md §3.1; ADR-004 D4): committed-finding curation and
 * generative-draft curation share one append-only table — same lifecycle, same
 * audit consumer.
 *
 * <p>{@code FINDING} subjects reference {@code findings.id}; {@code DRAFT}
 * subjects reference the AI_GENERATIVE {@code assertions.id} (the Assertion
 * row IS the durable draft store — rejection needs no entity row at all).
 */
public enum CurationSubjectKind {
    FINDING,
    DRAFT
}

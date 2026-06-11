package com.meridian.substrate.engine.agent.exception;

/**
 * Deploy-gate per-field 400 (engine-spec agents.md §5.4): each rejected field
 * carries its STRUCTURED code in the envelope {@code errors[].code} — e.g.
 * {@code AGENT_PROVENANCE_MISSING_OVERSIGHT} — so scripted operators and
 * Testers can branch on the exact provenance gap, not on prose. Mapped to
 * HTTP 400 by the app-module {@code GlobalExceptionHandler}.
 *
 * <p>The §5.4 named codes are spec-pinned. {@code AGENT_SPEC_MISSING_READINESS_SOURCE},
 * {@code AGENT_SPEC_READINESS_SOURCE_WITHOUT_SCORE} and
 * {@code AGENT_SPEC_GATE_PREDICATE_MISMATCH} are implementation-named codes
 * for the §5.4 "readinessSource present iff agentReadinessScore present" rule
 * and the §9.1 "predicate NULL iff UNSTRUCTURED" rule — the rules are binding,
 * the code NAMES are flagged as implementation choices (not spec-pinned).
 */
public class AgentDeployValidationException extends RuntimeException {

    /** Envelope {@code errors[].code} values (spec §5.4). */
    public enum Code {
        AGENT_PROVENANCE_MISSING_DEPLOYED_BY,
        AGENT_PROVENANCE_MISSING_CRITERIA,
        AGENT_PROVENANCE_MISSING_OVERSIGHT,
        AGENT_SPEC_MISSING_SUPERVISORY_MODEL,
        AGENT_SPEC_MISSING_EXECUTION_LOCUS,
        /** Score present without {@code readinessSource} (§5.4 pairing rule; P7 discipline). */
        AGENT_SPEC_MISSING_READINESS_SOURCE,
        /** {@code readinessSource} present without a score (§5.4 pairing rule). */
        AGENT_SPEC_READINESS_SOURCE_WITHOUT_SCORE,
        /** STRUCTURED gate without predicate, or UNSTRUCTURED gate with one (§9.1). */
        AGENT_SPEC_GATE_PREDICATE_MISMATCH
    }

    private final Code code;
    private final String field;

    public AgentDeployValidationException(Code code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public Code getCode() {
        return code;
    }

    /** The rejected request field, surfaced in {@code errors[].field}. */
    public String getField() {
        return field;
    }
}

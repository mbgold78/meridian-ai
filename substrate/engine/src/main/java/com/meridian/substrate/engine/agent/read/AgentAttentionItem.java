package com.meridian.substrate.engine.agent.read;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /agents/attention} row (engine-spec agents.md §13.2; ADR-003
 * D15) — shaped exactly like the cascade engine's {@code AttentionItem}
 * ({@code kind/tone/headline/since}) so the app-module composed
 * {@code GET /api/v1/attention} feed merges both engines read-side with zero
 * engine-to-engine coupling (the enum types are deliberately NOT imported
 * from {@code engine.cascade} — same wire vocabulary, separate types).
 *
 * <p>Tone mapping is binding (§13.2): {@code agent-in-intervention} and
 * {@code control-violation} are <em>destructive</em> — leader-attention
 * semantics rendered beside decline-pending cascades ([CASCADE-PROPAGATION]),
 * never failure semantics; {@code agent-paused} / {@code control-unconfirmed}
 * / {@code criteria-at-risk} → warn; {@code human-verify-due} → info.
 */
public record AgentAttentionItem(
        String agentKey,
        UUID registrationId,
        Kind kind,
        Tone tone,
        String headline,
        Instant since
) {

    /** The §13.2 kind set — closed; no kind exists outside these six. */
    public enum Kind {
        AGENT_IN_INTERVENTION("agent-in-intervention"),
        CONTROL_VIOLATION("control-violation"),
        AGENT_PAUSED("agent-paused"),
        CONTROL_UNCONFIRMED("control-unconfirmed"),
        CRITERIA_AT_RISK("criteria-at-risk"),
        HUMAN_VERIFY_DUE("human-verify-due");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wireValue() {
            return wire;
        }
    }

    /** Cascade-shape tone vocabulary; destructive = leader-attention, not failure. */
    public enum Tone {
        INFO("info"),
        WARN("warn"),
        DESTRUCTIVE("destructive");

        private final String wire;

        Tone(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wireValue() {
            return wire;
        }
    }
}

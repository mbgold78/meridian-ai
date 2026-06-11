package com.meridian.substrate.engine.cascade.read;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /cascades/attention} row (engine-spec cascade.md §6.1) — whose
 * action is demanded right now. Tone mapping is binding (queue #21 resolved):
 * {@code decline-pending-originator} and {@code respond-to-decline} are
 * <em>destructive</em> — leader-attention semantics rendered beside
 * in-intervention agents ([CASCADE-PROPAGATION]), never failure semantics;
 * {@code overdue} → warn; {@code level-looking} → info.
 */
public record AttentionItem(
        UUID cascadeId,
        Kind kind,
        Integer depth,
        String ownerLabel,
        Instant since,
        Tone tone,
        String headline
) {

    /** The §6.1 kind set — closed; no kind exists outside these four. */
    public enum Kind {
        LEVEL_LOOKING("level-looking"),
        RESPOND_TO_DECLINE("respond-to-decline"),
        DECLINE_PENDING_ORIGINATOR("decline-pending-originator"),
        OVERDUE("overdue");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wireValue() {
            return wire;
        }
    }

    /** §6.1 tone vocabulary; destructive = leader-attention, not failure. */
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

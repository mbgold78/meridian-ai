package com.meridian.substrate.engine.agent.read;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * One row of the cross-fleet Ops Center live panel ({@code GET /agents/feed}
 * — engine-spec agents.md §7.1): recent lifecycle EVENTS and telemetry
 * SIGHTINGS merged at read, newest first. Read-side composition only — no
 * feed table exists.
 */
public record AgentFeedItem(
        Kind kind,
        String agentKey,
        UUID agentRegistrationId,
        String headline,
        Instant at,
        AgentEventView event,
        AgentSightingView sighting) {

    /** Source discriminator; kebab on the wire like every agent enum (spec §3). */
    public enum Kind {
        EVENT, SIGHTING;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static AgentFeedItem ofEvent(String agentKey, UUID registrationId,
                                        String headline, AgentEventView event) {
        return new AgentFeedItem(Kind.EVENT, agentKey, registrationId, headline,
                event.occurredAt(), event, null);
    }

    public static AgentFeedItem ofSighting(String agentKey, UUID registrationId,
                                           String headline, AgentSightingView sighting) {
        return new AgentFeedItem(Kind.SIGHTING, agentKey, registrationId, headline,
                sighting.seenAt(), null, sighting);
    }
}

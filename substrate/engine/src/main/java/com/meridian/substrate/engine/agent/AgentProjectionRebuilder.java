package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentSighting;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentRetiredReason;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic replay of the append-only {@code agent_status_events} log →
 * the ⟲ registration projections (engine-spec agents.md §4/§16.2 #7; ADR-003
 * D1; cascade §10 idiom). Under the registration lock the log replays in
 * {@code event_seq} order, re-deriving {@code operational_mode},
 * {@code mode_before_pause}, {@code open_intervention_count},
 * {@code event_seq}, {@code shadow_entered_at}, {@code promotion_requested_at},
 * the retired block, the control-confirmation block and
 * {@code mcp_principal_id}; the liveness block re-derives from SIGHTINGS
 * (never events, never row recency — invariant I7).
 *
 * <p><b>Immutable deploy fields are VERIFIED against the DEPLOYED snapshot
 * (assert-equal), never rewritten</b> (invariant I2): a mismatch means the
 * immutable half of the row drifted — refuse loudly, never silently repair.
 * Gate CONTENT and oversight links are first-class rows, not ⟲ projections —
 * untouched here (gate standing belongs to the §9 evaluation passes;
 * OVERSIGHT_CHANGED payloads carry the before/after audit).
 *
 * <p>Notifications are NOT replayed: the outbox is history, not a projection.
 * {@code last_attested_by_label} keeps its current value when no
 * LIVENESS_ATTESTED event exists (the 6a telemetry attestation lane carries
 * no event; the label is not derivable — documented, asserted in tests via
 * the M15 verb path).
 */
@Service
public class AgentProjectionRebuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentProjectionRebuilder.class);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentSightingRepository sightingRepository;

    public AgentProjectionRebuilder(AgentRegistrationRepository registrationRepository,
                                    AgentStatusEventRepository eventRepository,
                                    AgentSightingRepository sightingRepository) {
        this.registrationRepository = registrationRepository;
        this.eventRepository = eventRepository;
        this.sightingRepository = sightingRepository;
    }

    /** Rebuilds every registration of a domain; returns the rebuilt ids. */
    @Ungoverned(reason = "operator recovery: deterministic projection replay from the append-only"
            + " event log (engine-spec agents.md §16.2 #7, ADR-003 D1) — creates no new business"
            + " state, so it is not one of the three ADR D2 PEPs")
    @Transactional
    public List<UUID> rebuildAll(UUID domainId) {
        List<UUID> rebuilt = new ArrayList<>();
        for (AgentRegistration reg : registrationRepository.findByDomainId(domainId)) {
            rebuild(reg.getId());
            rebuilt.add(reg.getId());
        }
        log.info("Rebuilt agent projections for domain [domainId={}, agents={}]",
                domainId, rebuilt.size());
        return rebuilt;
    }

    /** Rebuilds one registration's ⟲ projections from its event log, under the lock. */
    @Ungoverned(reason = "operator recovery: deterministic projection replay from the append-only"
            + " event log (engine-spec agents.md §16.2 #7, ADR-003 D1) — creates no new business"
            + " state, so it is not one of the three ADR D2 PEPs")
    @Transactional
    public AgentRegistration rebuild(UUID agentRegistrationId) {
        AgentRegistration reg = registrationRepository.findByIdForUpdate(agentRegistrationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "agent registration not found: " + agentRegistrationId));
        List<AgentStatusEvent> events =
                eventRepository.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId());
        if (events.isEmpty() || events.get(0).getEventType() != AgentEventType.DEPLOYED
                || events.get(0).getEventSeq() != 1) {
            throw new IllegalStateException("agent " + agentRegistrationId
                    + " has no DEPLOYED event at event_seq 1 — projections are not rebuildable"
                    + " (invariant I10)");
        }
        verifyImmutableDeployArtifacts(reg, events.get(0));

        // Reset the ⟲ fields to the post-DEPLOYED baseline; replay re-derives them.
        JsonNode snapshot = events.get(0).getPayload();
        boolean bornLive = "live".equals(snapshot.path("operationalMode").asText());
        reg.setOperationalMode(bornLive ? AgentOperationalMode.LIVE : AgentOperationalMode.SHADOW);
        reg.setShadowEnteredAt(bornLive ? null : events.get(0).getOccurredAt());
        reg.setModeBeforePause(null);
        reg.setOpenInterventionCount(0);
        reg.setPromotionRequestedAt(null);
        reg.setRetiredAt(null);
        reg.setRetiredReason(null);
        reg.setRetiredByLabel(null);
        reg.setControlPendingVerb(null);
        reg.setControlRequestedAt(null);
        reg.setControlAcknowledgedAt(null);
        reg.setControlConfirmedAt(null);
        reg.setControlConfirmedByLabel(null);
        reg.setMcpPrincipalId(snapshot.hasNonNull("mcpPrincipalId")
                ? snapshot.get("mcpPrincipalId").asText() : null);

        int expectedSeq = 1;
        for (AgentStatusEvent event : events) {
            if (event.getEventSeq() != expectedSeq++) {
                throw new IllegalStateException("agent " + agentRegistrationId
                        + " event log is not gapless at event_seq " + event.getEventSeq()
                        + " (uq_ase_seq should have prevented this — refusing to rebuild)");
            }
            apply(reg, event);
        }
        reg.setEventSeq(events.get(events.size() - 1).getEventSeq());   // I10 watermark

        rebuildLivenessFromSightings(reg, events);
        AgentRegistration saved = registrationRepository.save(reg);
        log.info("Rebuilt agent projections [key={}, events={}, mode={}, openInterventions={}]",
                saved.getAgentKey(), events.size(), saved.getOperationalMode(),
                saved.getOpenInterventionCount());
        return saved;
    }

    // ------------------------------------------------------------------ replay

    /** The same transition semantics the live verb path applies (§5.1), as a pure replay. */
    private void apply(AgentRegistration reg, AgentStatusEvent event) {
        switch (event.getEventType()) {
            case DEPLOYED -> { /* baseline applied above */ }
            case PROMOTION_REQUESTED -> reg.setPromotionRequestedAt(event.getOccurredAt());
            case PROMOTED -> {
                reg.setOperationalMode(AgentOperationalMode.LIVE);
                reg.setPromotionRequestedAt(null);
            }
            case PROMOTION_REJECTED -> reg.setPromotionRequestedAt(null);
            case PAUSED -> {
                reg.setModeBeforePause(reg.getOperationalMode());
                reg.setOperationalMode(AgentOperationalMode.PAUSED);
                openControl(reg, "pause", event);
            }
            case RESUMED -> {
                reg.setOperationalMode(reg.getModeBeforePause() != null
                        ? reg.getModeBeforePause() : AgentOperationalMode.SHADOW);
                reg.setModeBeforePause(null);
                reg.setControlPendingVerb(null);
                reg.setControlRequestedAt(null);
                reg.setControlAcknowledgedAt(null);
                reg.setControlConfirmedAt(null);
                reg.setControlConfirmedByLabel(null);
            }
            case THROTTLE_SET, THROTTLE_CLEARED -> {
                /* budgets are first-class rows, not ⟲ registration projections */
            }
            case KILLED -> terminal(reg, event, AgentRetiredReason.KILLED, "kill");
            case RETIRED -> terminal(reg, event, AgentRetiredReason.RETIRED, "retire");
            case INTERVENTION_OPENED ->
                    reg.setOpenInterventionCount(reg.getOpenInterventionCount() + 1);
            case INTERVENTION_ACKNOWLEDGED -> { /* overlay-row fact only */ }
            case INTERVENTION_RESOLVED -> {
                reg.setOpenInterventionCount(Math.max(0, reg.getOpenInterventionCount() - 1));
                if ("DEMOTED_TO_SHADOW".equals(event.getPayload() != null
                        ? event.getPayload().path("resolution").asText() : null)) {
                    reg.setOperationalMode(AgentOperationalMode.SHADOW);
                    reg.setShadowEnteredAt(event.getOccurredAt());   // reset on demotion (§4)
                }
            }
            case OVERSIGHT_CHANGED -> { /* links are first-class rows; payload is the audit */ }
            case CONTROL_ACKNOWLEDGED -> {
                if (reg.getControlAcknowledgedAt() == null) {
                    reg.setControlAcknowledgedAt(event.getOccurredAt());
                }
            }
            case CONTROL_CONFIRMED -> {
                reg.setControlConfirmedAt(event.getOccurredAt());
                reg.setControlConfirmedByLabel(event.getActorLabel());
            }
            case CONTROL_VIOLATION_OBSERVED -> { /* 6c zombie flow — intervention rows carry it */ }
            case LIVENESS_ATTESTED -> reg.setLastAttestedByLabel(event.getActorLabel());
            case PRINCIPAL_BOUND -> reg.setMcpPrincipalId(event.getPayload() != null
                    && event.getPayload().hasNonNull("mcpPrincipalId")
                    ? event.getPayload().get("mcpPrincipalId").asText() : null);
            case PRINCIPAL_REVOKED -> reg.setMcpPrincipalId(null);
            case CRITERIA_STANDING_CHANGED -> { /* gate-row denormalization, §9 passes own it */ }
            case DISPATCH -> { /* §14 provenance digest — log-only */ }
        }
    }

    private void terminal(AgentRegistration reg, AgentStatusEvent event,
                          AgentRetiredReason reason, String verb) {
        reg.setOperationalMode(AgentOperationalMode.RETIRED);
        reg.setModeBeforePause(null);
        reg.setRetiredAt(event.getOccurredAt());
        reg.setRetiredReason(reason);
        reg.setRetiredByLabel(event.getActorLabel());
        openControl(reg, verb, event);
    }

    private void openControl(AgentRegistration reg, String verb, AgentStatusEvent event) {
        reg.setControlPendingVerb(verb);
        reg.setControlRequestedAt(event.getOccurredAt());
        reg.setControlAcknowledgedAt(null);
        reg.setControlConfirmedAt(null);
        reg.setControlConfirmedByLabel(null);
    }

    // ------------------------------------------------------------- liveness (I7)

    /**
     * Liveness re-derives from sightings ONLY — never events, never row
     * recency. Tie-break discipline: the deterministic
     * {@code seenAt DESC, createdAt ASC, id ASC} variants mirror the live
     * rule ({@code recordSighting} overwrites only on STRICTLY-later, so the
     * FIRST recorded channel wins an equal-instant tie) — equal-instant
     * sightings are guaranteed under the demo fixed clock, and an arbitrary
     * tied row would flip {@code last_seen_via} live-vs-rebuild (§16.2 #7
     * rebuild-equality).
     */
    private void rebuildLivenessFromSightings(AgentRegistration reg, List<AgentStatusEvent> events) {
        AgentSighting latest = sightingRepository
                .findFirstByAgentRegistrationIdOrderBySeenAtDescCreatedAtAscIdAsc(reg.getId())
                .orElse(null);
        reg.setLastSeenAt(latest == null ? null : latest.getSeenAt());
        reg.setLastSeenVia(latest == null ? null : latest.getChannel());
        AgentSighting attested = sightingRepository
                .findFirstByAgentRegistrationIdAndChannelOrderBySeenAtDescCreatedAtAscIdAsc(
                        reg.getId(), AgentSightingChannel.ATTESTATION).orElse(null);
        reg.setLastAttestedAt(attested == null ? null : attested.getSeenAt());
        if (attested == null) {
            boolean everAttested = events.stream()
                    .anyMatch(e -> e.getEventType() == AgentEventType.LIVENESS_ATTESTED);
            if (!everAttested) {
                reg.setLastAttestedByLabel(null);
            }
        }
        // last_attested_by_label: latest LIVENESS_ATTESTED actor when evented
        // (applied during replay); the 6a telemetry lane's label survives as-is.
    }

    // -------------------------------------------------------------- I2 assertions

    /**
     * §16.2 #7: immutable deploy fields assert-equal against the DEPLOYED
     * snapshot — NEVER rewritten; drift is a refusal, not a repair.
     */
    private void verifyImmutableDeployArtifacts(AgentRegistration reg, AgentStatusEvent deployed) {
        JsonNode s = deployed.getPayload();
        if (s == null || s.isNull()) {
            throw new IllegalStateException("agent " + reg.getId()
                    + ": DEPLOYED event carries no frozen spec snapshot (invariant I2)");
        }
        requireEqual(reg.getId(), "agentKey", reg.getAgentKey(), s.path("agentKey").asText(null));
        requireEqual(reg.getId(), "deployedByLabel", reg.getDeployedByLabel(),
                s.path("deployedByLabel").asText(null));
        requireEqual(reg.getId(), "deploymentCriteria", reg.getDeploymentCriteria(),
                s.path("deploymentCriteria").asText(null));
        requireEqual(reg.getId(), "criteriaDigest", reg.getCriteriaDigest(),
                s.path("criteriaDigest").asText(null));
        requireEqual(reg.getId(), "executionLocus", reg.getExecutionLocus().wireValue(),
                s.path("executionLocus").asText(null));
        requireEqual(reg.getId(), "supervisoryModel", reg.getSupervisoryModel().wireValue(),
                s.path("supervisoryModel").asText(null));
        requireEqual(reg.getId(), "deployedAt", reg.getDeployedAt(), deployed.getOccurredAt());
    }

    private static void requireEqual(UUID id, String field, Object onRow, Object inSnapshot) {
        if (!Objects.equals(onRow, inSnapshot)) {
            throw new IllegalStateException("agent " + id + ": immutable deploy field '" + field
                    + "' drifted from the DEPLOYED snapshot (row=" + onRow + ", snapshot="
                    + inSnapshot + ") — invariant I2; refusing to rebuild over drifted identity");
        }
    }
}

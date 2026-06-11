package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * The ADR-003 D14 hash-chain mirror (engine-spec agents.md §11) — bounded to
 * EXACTLY three event classes: <b>DEPLOYED</b> (anchor carries
 * {@code criteria_digest} — mandate creation becomes tamper-evident),
 * <b>PROMOTED</b> (production entry, carries the consumed review id),
 * <b>RETIRED/KILLED</b> (termination, carries {@code retired_reason} — kill
 * and orderly retirement are both chain-anchored). OVERSIGHT_CHANGED is NOT
 * mirrored (high-frequency-adjacent operational act; lives in the
 * trigger-protected append-only log).
 *
 * <p><b>Shared mechanism, never forked (D14 coordination rule):</b> anchors
 * append via {@link DecisionLogService#appendDecision} under its existing
 * per-domain append lock — agent code NEVER writes the chain directly; the
 * canonical-JSON layout is the one pinned mechanism in {@code engine.decision}
 * (the contracts D14 mirror rides the same one). Additive
 * {@link DecisionType} values {@code AGENT_DEPLOYED / AGENT_PROMOTED /
 * AGENT_RETIRED} discriminate the agent anchors.
 *
 * <p><b>Pinned BEFORE enabled (port-map trap #3):</b> the anchor field layout
 * below (title/description/metadata key set and insertion order) is frozen by
 * {@code AgentMirrorHashVectorTest} — never regenerate the pinned constants to
 * make a failing test pass; existing engagement chains would silently change
 * meaning.
 *
 * <p><b>Chain inputs are immutable identifiers only</b> ([DECISIONLOG-COVERAGE];
 * ADR-003 D14): {@code agent_key}, row UUIDs, event type/seq, the stable
 * {@code actor_member_id} (omitted when the persona carried none — actor
 * LABELS are display names and never enter the chain), {@code occurred_at},
 * digests. Reasons/prose live on the event row, referenced by id.
 *
 * <p><b>Transaction posture (contracts-mirror precedent, documented):</b>
 * {@code DecisionLogService} commits each append in {@code REQUIRES_NEW}
 * inside its lock — appending from within the verb transaction would anchor
 * acts that might still roll back. The mirror therefore registers an
 * {@code afterCommit} synchronization (act-then-anchor); anchor failure is
 * logged at ERROR and never disturbs committed governance state. The
 * resulting hash is then written back to
 * {@code agent_status_events.decision_log_hash} in its own small transaction
 * — the ONE sanctioned mutation of an event row (cross-navigation, spec §4);
 * the V6_2 Postgres trigger permits exactly that NULL→value single-column
 * transition and nothing else.
 */
@Service
public class AgentGovernanceChainMirror {

    private static final Logger log = LoggerFactory.getLogger(AgentGovernanceChainMirror.class);

    /** Frozen anchor discriminators (metadata.anchor + title suffix). */
    static final String ANCHOR_DEPLOYED = "AGENT_DEPLOYED";
    static final String ANCHOR_PROMOTED = "AGENT_PROMOTED";
    static final String ANCHOR_RETIRED = "AGENT_RETIRED";

    private final DecisionLogService decisionLogService;
    private final AgentStatusEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate writeBackTx;

    public AgentGovernanceChainMirror(DecisionLogService decisionLogService,
                                      AgentStatusEventRepository eventRepository,
                                      ObjectMapper objectMapper,
                                      PlatformTransactionManager transactionManager) {
        this.decisionLogService = decisionLogService;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.writeBackTx = new TransactionTemplate(transactionManager);
        this.writeBackTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** §11: DEPLOYED anchor — canonical JSON includes {@code criteria_digest}. */
    public void onDeployed(AgentRegistration reg, AgentStatusEvent event) {
        DesignDecision anchor = baseAnchor(reg, DecisionType.AGENT_DEPLOYED, ANCHOR_DEPLOYED,
                "Agent " + reg.getAgentKey() + " deployed with a frozen mandate; criteria"
                        + " digest anchored (ADR-003 D5/D14).");
        ObjectNode metadata = baseMetadata(reg, event, ANCHOR_DEPLOYED);
        metadata.put("criteriaDigest", reg.getCriteriaDigest());
        metadata.put("occurredAt", event.getOccurredAt().toString());
        anchor.setMetadata(metadata);
        appendAfterCommit(anchor, event.getId(), reg.getAgentKey(), ANCHOR_DEPLOYED);
    }

    /** §11: PROMOTED anchor — carries the single-use consumed review id. */
    public void onPromoted(AgentRegistration reg, AgentStatusEvent event, UUID consumedReviewId) {
        DesignDecision anchor = baseAnchor(reg, DecisionType.AGENT_PROMOTED, ANCHOR_PROMOTED,
                "Agent " + reg.getAgentKey() + " promoted shadow to live against a consumed"
                        + " APPROVED promotion review (ADR-003 D10/D14).");
        ObjectNode metadata = baseMetadata(reg, event, ANCHOR_PROMOTED);
        metadata.put("consumedReviewId",
                consumedReviewId == null ? null : consumedReviewId.toString());
        metadata.put("occurredAt", event.getOccurredAt().toString());
        anchor.setMetadata(metadata);
        appendAfterCommit(anchor, event.getId(), reg.getAgentKey(), ANCHOR_PROMOTED);
    }

    /** §11: RETIRED anchor — kill AND orderly retire, discriminated by {@code retiredReason}. */
    public void onRetired(AgentRegistration reg, AgentStatusEvent event) {
        DesignDecision anchor = baseAnchor(reg, DecisionType.AGENT_RETIRED, ANCHOR_RETIRED,
                "Agent " + reg.getAgentKey() + " reached the terminal-absorbing retired state;"
                        + " termination is chain-anchored either way (ADR-003 D4/D14).");
        ObjectNode metadata = baseMetadata(reg, event, ANCHOR_RETIRED);
        metadata.put("retiredReason",
                reg.getRetiredReason() == null ? null : reg.getRetiredReason().name());
        metadata.put("occurredAt", event.getOccurredAt().toString());
        anchor.setMetadata(metadata);
        appendAfterCommit(anchor, event.getId(), reg.getAgentKey(), ANCHOR_RETIRED);
    }

    // ------------------------------------------------------------------ internals

    /** Shared frozen layout: domain/type/status/title/description. */
    private DesignDecision baseAnchor(AgentRegistration reg, DecisionType type,
                                      String anchorType, String description) {
        DesignDecision decision = new DesignDecision();
        decision.setDomainId(reg.getDomainId());
        decision.setDecisionType(type);
        // APPROVED records a COMPLETED governance act — the contracts-mirror
        // status choice, frozen by the pinned vectors.
        decision.setStatus(DecisionStatus.APPROVED);
        decision.setTitle("agent:" + reg.getAgentKey() + ":" + anchorType);
        decision.setDescription(description);
        return decision;
    }

    /**
     * Frozen insertion order: anchor, agentRegistrationId, agentKey, eventId,
     * eventType, eventSeq, [actorMemberId — only when present],
     * [class-specific keys], occurredAt (appended by each anchor method).
     */
    private ObjectNode baseMetadata(AgentRegistration reg, AgentStatusEvent event,
                                    String anchorType) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("anchor", anchorType);
        metadata.put("agentRegistrationId", reg.getId().toString());
        metadata.put("agentKey", reg.getAgentKey());
        metadata.put("eventId", event.getId().toString());
        metadata.put("eventType", event.getEventType().name());
        metadata.put("eventSeq", event.getEventSeq());
        if (event.getActorMemberId() != null) {
            metadata.put("actorMemberId", event.getActorMemberId().toString());
        }
        return metadata;
    }

    /**
     * Appends after the surrounding verb transaction commits (class javadoc
     * posture); outside a transaction (tests, tooling) appends immediately.
     * Failures are loud, never destructive.
     */
    private void appendAfterCommit(DesignDecision anchor, UUID eventId,
                                   String agentKey, String anchorType) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    append(anchor, eventId, agentKey, anchorType);
                }
            });
        } else {
            append(anchor, eventId, agentKey, anchorType);
        }
    }

    private void append(DesignDecision anchor, UUID eventId, String agentKey, String anchorType) {
        try {
            DesignDecision saved = decisionLogService.appendDecision(anchor);
            writeBackHash(eventId, saved.getCurrentHash());
            log.info("ADR-003 D14 anchor appended [agentKey={}, anchor={}, hash={}]",
                    agentKey, anchorType, saved.getCurrentHash());
        } catch (RuntimeException ex) {
            // afterCommit exceptions would propagate to the committing caller;
            // the governance act is already durable — the missing anchor is an
            // operator-visible ERROR, never a rollback of committed state.
            log.error("ADR-003 D14 anchor FAILED [agentKey={}, anchor={}] — committed agent"
                    + " state stands; chain anchor missing", agentKey, anchorType, ex);
        }
    }

    /**
     * The sanctioned cross-navigation write-back (spec §4/§11): sets
     * {@code decision_log_hash} on the mirrored event — NULL→value, single
     * column, own transaction. The V6_2 trigger permits exactly this
     * transition; everything else on the audit log stays append-only (I4).
     */
    private void writeBackHash(UUID eventId, String hash) {
        writeBackTx.executeWithoutResult(status ->
                eventRepository.findById(eventId).ifPresent(event -> {
                    if (event.getDecisionLogHash() != null) {
                        log.warn("decision_log_hash already set — leaving the original"
                                + " [eventId={}]", eventId);
                        return;
                    }
                    event.setDecisionLogHash(hash);
                    eventRepository.save(event);
                }));
    }
}

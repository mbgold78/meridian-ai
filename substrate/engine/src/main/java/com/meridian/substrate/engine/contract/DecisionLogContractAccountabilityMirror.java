package com.meridian.substrate.engine.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;

/**
 * The REAL ADR-002 D14 hash-chain mirror (replaces the 3a
 * {@link NoopContractAccountabilityMirror} seam): contract ACTIVATION, both
 * DECLINE transitions, and breach WAIVER are mirrored as
 * {@code DecisionType.CONTRACT_ACCOUNTABILITY} entries into the existing
 * {@code engine.decision} SHA-256 chain via {@link DecisionLogService} under
 * its per-domain append lock — contract code NEVER writes the chain directly
 * (spec §10.7). Bounded to exactly these three anchors (chain-volume /
 * append-lock contention, ADR D14).
 *
 * <p><b>Pinned BEFORE enabled (port-map trap #3):</b> the anchor field layout
 * below (title/description/metadata key set and insertion order) is frozen by
 * {@code ContractMirrorHashVectorTest} — pinned hashes were generated from
 * this exact layout. Changing ANY byte of the layout breaks existing chains'
 * re-verification semantics; never regenerate the pinned constants to make a
 * failing test pass.
 *
 * <p><b>Chain inputs are immutable identifiers only</b> ([DECISIONLOG-COVERAGE];
 * ADR D14): {@code contract_key}, row UUIDs, period keys, actor ids — never
 * display titles or operator prose (waiver rationale lives in
 * {@code contract_events}, referenced by breach id).
 *
 * <p><b>Transaction posture (documented deviation from the §2.2
 * "same transaction" side-effect column):</b> {@code DecisionLogService}
 * commits each append inside its per-domain lock ({@code REQUIRES_NEW}) — an
 * append joined to the caller's transaction would escape the locked region
 * and re-open the chain-fork window (§14.4 "concurrent contract-event +
 * design-decision appends do not fork"). The mirror therefore registers an
 * {@code afterCommit} synchronization: the anchor is appended immediately
 * after the business transaction commits (act-then-anchor; an anchor for a
 * rolled-back act would be a worse audit artifact than a logged-loud missing
 * anchor in the tiny crash window). Anchor failure is logged at ERROR and
 * never disturbs the committed governance state.
 */
public class DecisionLogContractAccountabilityMirror implements ContractAccountabilityMirror {

    private static final Logger log =
            LoggerFactory.getLogger(DecisionLogContractAccountabilityMirror.class);

    /** Frozen anchor discriminators (metadata.anchor + title suffix). */
    static final String ANCHOR_ACTIVATED = "CONTRACT_ACTIVATED";
    static final String ANCHOR_DECLINED = "CONTRACT_DECLINED";
    static final String ANCHOR_BREACH_WAIVED = "BREACH_WAIVED";

    private final DecisionLogService decisionLogService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public DecisionLogContractAccountabilityMirror(DecisionLogService decisionLogService,
                                                   Clock clock,
                                                   ObjectMapper objectMapper) {
        this.decisionLogService = decisionLogService;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onActivated(Contract contract) {
        DesignDecision anchor = baseAnchor(contract, ANCHOR_ACTIVATED,
                "Coordination contract " + contract.getContractKey()
                        + " activated: all required parties accepted.");
        anchor.setMetadata(baseMetadata(contract, ANCHOR_ACTIVATED));
        appendAfterCommit(anchor, contract.getContractKey(), ANCHOR_ACTIVATED);
    }

    @Override
    public void onDeclined(Contract contract) {
        DesignDecision anchor = baseAnchor(contract, ANCHOR_DECLINED,
                "Coordination contract " + contract.getContractKey()
                        + " resolved DECLINED: a legitimate first-class terminal resolution,"
                        + " never a violation.");
        anchor.setMetadata(baseMetadata(contract, ANCHOR_DECLINED));
        appendAfterCommit(anchor, contract.getContractKey(), ANCHOR_DECLINED);
    }

    @Override
    public void onBreachWaived(Contract contract, ContractBreach breach) {
        DesignDecision anchor = baseAnchor(contract, ANCHOR_BREACH_WAIVED,
                "Breach " + breach.getId() + " on coordination contract "
                        + contract.getContractKey()
                        + " waived by a human actor (ADR D16); rationale recorded in"
                        + " contract_events.");
        // Frozen insertion order: anchor, contractId, contractKey, breachId,
        // periodKey (omitted when null), resolutionActor (omitted when null),
        // occurredAt.
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("anchor", ANCHOR_BREACH_WAIVED);
        metadata.put("contractId", contract.getId().toString());
        metadata.put("contractKey", contract.getContractKey());
        metadata.put("breachId", breach.getId().toString());
        if (breach.getPeriodKey() != null) {
            metadata.put("periodKey", breach.getPeriodKey());
        }
        if (breach.getResolutionActor() != null) {
            metadata.put("resolutionActor", breach.getResolutionActor());
        }
        metadata.put("occurredAt", clock.instant().toString());
        anchor.setMetadata(metadata);
        appendAfterCommit(anchor, contract.getContractKey(), ANCHOR_BREACH_WAIVED);
    }

    // ------------------------------------------------------------------ internals

    /** Shared frozen layout: title/status/type/description. */
    private DesignDecision baseAnchor(Contract contract, String anchorType, String description) {
        DesignDecision decision = new DesignDecision();
        decision.setDomainId(contract.getDomainId());
        decision.setDecisionType(DecisionType.CONTRACT_ACCOUNTABILITY);
        // APPROVED: the anchor records a COMPLETED governance act — the
        // closest of the four reserved DecisionStatus values (none is a
        // perfect fit; choice frozen by the pinned vectors).
        decision.setStatus(DecisionStatus.APPROVED);
        decision.setTitle("contract:" + contract.getContractKey() + ":" + anchorType);
        decision.setDescription(description);
        return decision;
    }

    /** Frozen insertion order: anchor, contractId, contractKey, occurredAt. */
    private ObjectNode baseMetadata(Contract contract, String anchorType) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("anchor", anchorType);
        metadata.put("contractId", contract.getId().toString());
        metadata.put("contractKey", contract.getContractKey());
        metadata.put("occurredAt", clock.instant().toString());
        return metadata;
    }

    /**
     * Appends after the surrounding business transaction commits (class
     * javadoc posture); outside a transaction (tests, tooling) appends
     * immediately. Failures are loud, never destructive.
     */
    private void appendAfterCommit(DesignDecision anchor, String contractKey, String anchorType) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    append(anchor, contractKey, anchorType);
                }
            });
        } else {
            append(anchor, contractKey, anchorType);
        }
    }

    private void append(DesignDecision anchor, String contractKey, String anchorType) {
        try {
            DesignDecision saved = decisionLogService.appendDecision(anchor);
            log.info("ADR D14 anchor appended [contractKey={}, anchor={}, hash={}]",
                    contractKey, anchorType, saved.getCurrentHash());
        } catch (RuntimeException ex) {
            // afterCommit exceptions would propagate to the committing caller;
            // the governance act is already durable — the missing anchor is an
            // operator-visible ERROR, never a rollback of committed state.
            log.error("ADR D14 anchor FAILED [contractKey={}, anchor={}] — committed contract"
                    + " state stands; chain anchor missing", contractKey, anchorType, ex);
        }
    }
}

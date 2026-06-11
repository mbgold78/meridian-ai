package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persists {@code contract_enforcement_decisions} rows for the PDP
 * (engine-spec contracts.md §9/§10.4; ADR D17) and carries the §4.3 metrics
 * counters for the decisions that are deliberately NOT rowed.
 *
 * <p><b>Transaction posture:</b> every row is written in
 * {@code REQUIRES_NEW} — the §10.4 requirement exists so DENY rows survive
 * the business-transaction rollback, and the same posture is applied
 * uniformly: a decision is a fact about evaluation, recorded whether or not
 * the surrounding business transaction later commits (shadow {@code
 * WOULD_DENY} telemetry must not vanish when the business write fails for
 * unrelated reasons). Row volume is bounded by the matched-only default
 * (level policy lives in {@link ContractGateway}, mirroring the §4.3
 * pseudocode; retention is spec §15 #11 — flagged, not built).
 *
 * <p>Counters follow the {@code AdvisoryCheckService} v1 precedent
 * (in-process {@code AtomicLong}s, no metrics framework in the engine
 * module): {@code allow_unevaluated} (mode off), {@code allow_no_guard}
 * (empty guard set), {@code allow_unrowed} (evaluated, nothing matched,
 * level=matched-only).
 */
@Service
public class EnforcementDecisionWriter {

    private final ContractEnforcementDecisionRepository decisionRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    private final AtomicLong allowUnevaluatedCount = new AtomicLong();
    private final AtomicLong allowNoGuardCount = new AtomicLong();
    private final AtomicLong allowUnrowedCount = new AtomicLong();

    public EnforcementDecisionWriter(ContractEnforcementDecisionRepository decisionRepository,
                                     Clock clock,
                                     ObjectMapper objectMapper) {
        this.decisionRepository = decisionRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends one decision row in its own transaction and returns the row id
     * (the §4.5 {@code decisionId} citation). {@code reason} is truncated to
     * the column's 512 chars.
     */
    @Ungoverned(reason = "the PDP's own REQUIRES_NEW decision-row writer (§10.4/ADR D17) —"
            + " governing the gateway's audit write would recurse the gateway")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID row(GovernedAction action, GatewayDecision decision, List<UUID> clauseIds,
                    List<UUID> contractIds, String reason, EnforcementMode mode,
                    Integer latencyMs) {
        ContractEnforcementDecision row = new ContractEnforcementDecision();
        row.setDomainId(action.domainId());
        row.setActionType(action.actionType());
        row.setActorType(action.principal().type());
        row.setActorId(action.principal().id());
        row.setDecision(decision);
        row.setContractIds(jsonArray(contractIds));
        row.setClauseIds(jsonArray(clauseIds));
        row.setDispatchId(action.dispatchId());
        row.setReason(truncate(reason));
        row.setLatencyMs(latencyMs);
        row.setMode(mode.wire());
        row.setDecidedAt(clock.instant());
        return decisionRepository.save(row).getId();
    }

    // -------------------------------------------------- §4.3 metrics counters

    /** Mode off: gateway skipped, counter only (§1). */
    public void countAllowUnevaluated() {
        allowUnevaluatedCount.incrementAndGet();
    }

    /** Empty guard set for the action (§4.3). */
    public void countAllowNoGuard() {
        allowNoGuardCount.incrementAndGet();
    }

    /** Evaluated, no guard matched, level=matched-only ⇒ counter not row (D17). */
    public void countAllowUnrowed() {
        allowUnrowedCount.incrementAndGet();
    }

    public long allowUnevaluatedCount() {
        return allowUnevaluatedCount.get();
    }

    public long allowNoGuardCount() {
        return allowNoGuardCount.get();
    }

    public long allowUnrowedCount() {
        return allowUnrowedCount.get();
    }

    // ------------------------------------------------------------------ helpers

    private ArrayNode jsonArray(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        ArrayNode array = objectMapper.createArrayNode();
        ids.stream().distinct().forEach(id -> array.add(id.toString()));
        return array;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= 512) {
            return reason;
        }
        return reason.substring(0, 512);
    }
}
